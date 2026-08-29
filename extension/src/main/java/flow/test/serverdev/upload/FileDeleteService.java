package flow.test.serverdev.upload;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import flow.test.serverdev.audit.UploadAuditRepository;
import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;
import flow.test.serverdev.storage.ObjectStorage;

/**
 * 업로드된 파일을 지운다. (DELETE /api/files/&#123;fileId&#125;)
 *
 * <p><b>객체는 지우고 기록은 남긴다.</b> 감사 행을 지우면 "무엇이 왜 올라갔는가" 를 함께
 * 잃는다 — 이 서비스의 존재 이유가 사라진다. 그래서 지우는 것은 오브젝트 스토리지의 객체
 * 뿐이고, 기록에는 {@code deleted_at} 이라는 사실이 하나 더 붙는다. 삭제도 일어난 일이다.
 *
 * <p><b>★ 순서 — 소유권을 먼저 얻고, 얻었을 때만 객체를 지운다.</b> (SPEC §21.6 과 같은 근거)
 *
 * <p>반대로 하면 객체 삭제와 행 갱신 사이에서 후자가 실패했을 때 {@code deleted_at} 이
 * NULL 인데 객체는 없는 행이 남는다 — <b>목록에는 보이는데 다운로드는 404</b> 다.
 * 사용자는 목록에 있는 파일이 왜 안 받아지는지 알 수 없고, 조회로도 그 상태를 구분할 수 없다.
 *
 * <p>이 순서에서는 반대로 <b>"객체를 못 지웠어도 기록은 삭제로 확정된다"</b> 가 된다.
 * 고아 객체가 남지만 행에 {@code stored_key} 가 그대로라 <b>무엇이 남았는지 찾아낼 수
 * 있다.</b> 추적 가능한 고아와, 아무도 모르게 깨진 목록 항목 중에서는 앞의 것이 낫다 —
 * {@code PendingUploadSweeper} 가 같은 이유로 같은 순서를 택했다.
 *
 * <h2>★ 고아 객체를 자동으로 다시 걷어가지 않는 이유 (외부 리뷰 CodeRabbit)</h2>
 *
 * <p>"삭제 실패를 재시도 가능하게" 라는 지적을 받았다. 스위퍼를 확장하는 길을 검토했고,
 * <b>이 과제 범위에서는 넣지 않기로 했다.</b> 근거는 셋이다.
 *
 * <ol>
 *   <li><b>스키마를 하나 더 늘려야 한다.</b> 스위퍼가 이 고아를 집으려면 "삭제로 확정됐고
 *       객체는 아직 남아 있다" 를 <b>DB만 보고</b> 알 수 있어야 하는데, 지금은 알 수 없다 —
 *       {@code deleted_at} 만으로는 객체 제거가 성공한 행과 실패한 행이 구분되지 않는다.
 *       {@code object_removed_at} 같은 컬럼을 더하고, CHECK 와 트리거 규칙(1회 단방향)과
 *       부분 인덱스와 스위퍼의 두 번째 경로를 함께 만들어야 한다. 이 기능만 한 크기다.
 *   <li><b>컬럼 없이 재시도하면 수렴하지 않는다.</b> "삭제됐고 {@code stored_key} 가 있는
 *       행" 을 다시 집으면 이미 정리된 행까지 매 주기 다시 집는다. S3 의 삭제는 없는 키에도
 *       성공하므로 <b>영원히 끝나지 않는 작업</b>이 된다.
 *   <li><b>더 정확한 답이 이미 있고, 그것은 스키마를 요구하지 않는다.</b> 고아의 정의는
 *       "버킷에는 있는데 살아 있는 행이 가리키지 않는 객체" 다. 버킷 목록과
 *       {@code result = 'ALLOWED' AND deleted_at IS NULL} 의 {@code stored_key} 집합을
 *       대조하면 <b>이 경로의 고아와 스위퍼가 남긴 고아를 한 번에</b> 찾는다. 컬럼을 더하는
 *       쪽은 이 경로의 고아만 잡는다 — 더 비싸고 덜 덮는다.
 * </ol>
 *
 * <p>정리 작업은 운영의 몫으로 남긴다. 지금 코드가 보장하는 것은 <b>고아가 조용히 생기지
 * 않는다</b> 는 것이다 — 실패는 {@code WARN} 으로 남고, 행은 {@code stored_key} 를 지운 적이
 * 없다. 자동 회수가 필요해지는 시점(삭제가 잦고 스토리지가 자주 흔들리는 환경)이 오면
 * 1번을 그대로 구현하면 된다.
 *
 * <p><b>{@code @Transactional} 을 걸지 않는다.</b> 걸면 소유권 UPDATE 가 객체 삭제 뒤에야
 * 커밋되어, 위에서 피하려던 상태가 트랜잭션 안에서 그대로 재현된다.
 */
@Service
public class FileDeleteService {

	private static final Logger log = LoggerFactory.getLogger(FileDeleteService.class);

	private final UploadAuditRepository repository;
	private final ObjectStorage storage;

	public FileDeleteService(UploadAuditRepository repository, ObjectStorage storage) {
		this.repository = repository;
		this.storage = storage;
	}

	/**
	 * 없거나, 이미 지웠거나, {@code ALLOWED} 가 아니면 <b>전부</b> {@code FILE_NOT_FOUND} 다.
	 *
	 * <p>구분해서 답하면 응답 차이만으로 "그 식별자는 존재하지만 차단됐다" 를 알아낼 수 있다.
	 * 다운로드(SPEC §7.6)가 같은 이유로 같은 선택을 했다.
	 */
	public void delete(UUID fileId) {
		if (repository.markDeleted(fileId) == 0) {
			throw new PolicyException(ErrorCode.FILE_NOT_FOUND,
				"요청하신 파일을 찾을 수 없습니다: " + fileId);
		}

		String storedKey = repository.findStoredKey(fileId).orElse(null);
		if (storedKey == null) {
			// ALLOWED 는 stored_key 가 NOT NULL 이라(ck_upload_audit_stored_key) 도달할 수 없다.
			// 그래도 조용히 넘기지 않는다 — 여기 오면 DB 제약이 무너졌다는 뜻이다.
			log.error("삭제 소유권을 얻었는데 저장 키가 없습니다. 스키마 제약을 확인해야 합니다: fileId={}",
				fileId);
			return;
		}

		removeObject(fileId, storedKey);
	}

	/**
	 * ★ 객체 삭제 실패를 <b>사용자에게 실패로 알리지 않는다.</b>
	 *
	 * <p>여기 도달했다면 행은 이미 삭제로 확정돼 커밋됐다. 파일은 목록에서도 다운로드에서도
	 * 사라졌고, 다시 시도하면 {@code 404} 를 받는다. 그 상태에서 {@code 503} 을 주면
	 * <b>"실패했으니 다시 해보라" 고 말해놓고 다시 하면 "그런 것 없다" 고 답하는</b> 셈이다.
	 * 사용자가 관측할 수 있는 상태와 우리가 보내는 답이 어긋난다.
	 *
	 * <p>남은 객체는 사용자의 문제가 아니라 우리 쪽 정리 과제다. {@code WARN} 으로 남기고
	 * {@code stored_key} 를 행에 그대로 둔다 — 클래스 주석의 3번이 그것을 근거로 삼는다.
	 * {@code PendingUploadSweeper.sweepOne} 이 같은 실패에 같은 판단을 한다.
	 */
	private void removeObject(UUID fileId, String storedKey) {
		try {
			storage.delete(storedKey);
		}
		catch (RuntimeException e) {
			log.warn("기록은 삭제로 확정했으나 객체를 지우지 못했습니다. 고아 객체가 남습니다 — "
					+ "버킷과 살아 있는 stored_key 를 대조하면 찾을 수 있습니다: fileId={}, storedKey={}",
				fileId, storedKey, e);
		}
	}
}
