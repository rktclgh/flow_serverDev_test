package flow.test.serverdev.upload;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import flow.test.serverdev.audit.UploadAudit;
import flow.test.serverdev.audit.UploadAuditRepository;

/**
 * 업로드된 파일의 목록. (GET /api/files)
 *
 * <p>지금까지 업로드는 되는데 <b>무엇이 올라갔는지 볼 방법이 없었다.</b> 이 조회가 그것을
 * 답한다. 근거는 감사 기록이다 — 같은 사실을 담는 테이블을 하나 더 두면 둘이 어긋날 자리만
 * 늘어난다(SPEC §7.6 의 판단과 같다).
 *
 * <p><b>페이지네이션을 만들지 않는다.</b> 과제 범위 밖이다. 그렇다고 전부 내보내면 행이
 * 쌓일수록 응답이 커지므로 {@link #MAX_FILES} 개에서 자른다. "상한 없음" 은 선택이 아니라
 * 미룬 문제다 — 나중에 페이지네이션을 붙일 때 이 상수가 첫 페이지 크기가 된다.
 */
@Service
public class FileListService {

	/** 한 번에 내보내는 최대 개수. 최신 것부터 이만큼만 보인다. */
	static final int MAX_FILES = 100;

	private final UploadAuditRepository repository;

	public FileListService(UploadAuditRepository repository) {
		this.repository = repository;
	}

	/**
	 * 살아 있는 {@code ALLOWED} 기록을 최신순으로 최대 {@value #MAX_FILES} 개.
	 *
	 * <p>읽기 전용 트랜잭션이다. 감사 기록은 이 경로에서 바뀌지 않으며, 변경 감지 대상에서
	 * 빠지므로 목록 조회가 실수로 UPDATE 를 만들 여지도 없다.
	 */
	@Transactional(readOnly = true)
	public List<FileSummary> list() {
		return repository.findVisible(PageRequest.of(0, MAX_FILES)).stream()
			.map(FileListService::toSummary)
			.toList();
	}

	/**
	 * 감사 행에서 <b>공개해도 되는 것만</b> 옮긴다.
	 *
	 * <p>엔티티를 그대로 직렬화하면 {@code client_ip}·{@code reason_code}·{@code stored_key}
	 * 가 통째로 나간다. 그것들은 관리자의 정보이고, 특히 {@code stored_key} 는 저장 위치를
	 * 그대로 알려주는 값이다.
	 */
	private static FileSummary toSummary(UploadAudit audit) {
		return new FileSummary(audit.fileId(), audit.originalFilename(), audit.sizeBytes(),
			audit.occurredAt());
	}
}
