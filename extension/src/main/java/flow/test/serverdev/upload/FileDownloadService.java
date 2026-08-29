package flow.test.serverdev.upload;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import flow.test.serverdev.audit.UploadAudit;
import flow.test.serverdev.audit.UploadAuditRepository;
import flow.test.serverdev.audit.UploadResult;
import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;
import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageObjectNotFoundException;

/**
 * 저장된 파일을 다시 꺼낸다. (SPEC §7.6, §21.8)
 *
 * <p><b>{@code ALLOWED} 인 행만 내보낸다.</b> 조건을 조회에 넣는 것이 중요하다 — 읽어와서
 * 상태를 보고 거르면, 그 분기를 지워도 "객체가 없어서" 404 가 나오는 경우가 많아 방어가
 * 사라진 것을 아무도 눈치채지 못한다.
 *
 * <p><b>지운 파일도 마찬가지다.</b> {@code deleted_at IS NULL} 을 조건에 넣지 않아도 대개는
 * 객체가 없어 404 가 나오지만, 그것은 우연이다 — 객체 삭제가 실패한 행에서는 지운 파일이
 * 그대로 내려간다. 조건이 쿼리에 있으면 그런 행은 애초에 손에 들어오지 않는다.
 *
 * <p>{@code PENDING}·{@code ERROR}·{@code BLOCKED} 를 상태별로 구분해 답하지 않는다.
 * 응답이 다르면 그 차이만으로 <b>"그 식별자는 존재하지만 차단됐다" 를 알아낼 수 있다.</b>
 * 감사 기록의 내용은 관리자의 것이지 요청자의 것이 아니다.
 */
@Service
public class FileDownloadService {

	private static final Logger log = LoggerFactory.getLogger(FileDownloadService.class);

	private final UploadAuditRepository repository;
	private final ObjectStorage storage;

	public FileDownloadService(UploadAuditRepository repository, ObjectStorage storage) {
		this.repository = repository;
		this.storage = storage;
	}

	public StoredFile load(UUID fileId) {
		UploadAudit audit = repository
			.findByFileIdAndResultAndDeletedAtIsNull(fileId, UploadResult.ALLOWED)
			.orElseThrow(() -> notFound(fileId));

		try {
			return new StoredFile(audit.originalFilename(), storage.load(audit.storedKey()));
		}
		catch (StorageObjectNotFoundException e) {
			// ALLOWED 인데 객체가 없다면 그것은 사용자의 문제가 아니라 우리 쪽 불일치다.
			// 그래도 503 을 주지 않는다 — 다시 시도해도 결과가 같고, 클라이언트가 할 수 있는
			// 일이 없다. 대신 로그에 남겨 사람이 조사할 수 있게 한다.
			log.error("ALLOWED 기록이 가리키는 객체가 없습니다: fileId={}, storedKey={}",
				fileId, audit.storedKey(), e);
			throw notFound(fileId);
		}
	}

	private static PolicyException notFound(UUID fileId) {
		return new PolicyException(ErrorCode.FILE_NOT_FOUND, "요청하신 파일을 찾을 수 없습니다: " + fileId);
	}
}
