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
 * 고아 객체가 남지만 행에 {@code stored_key} 가 그대로라 <b>무엇이 남았는지 조회로 찾을 수
 * 있다.</b> 추적 가능한 고아와, 아무도 모르게 깨진 목록 항목 중에서는 앞의 것이 낫다 —
 * {@code PendingUploadSweeper} 가 같은 이유로 같은 순서를 택했다.
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

		storage.delete(storedKey);
	}
}
