package flow.test.serverdev.upload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import flow.test.serverdev.audit.UploadAuditRepository;
import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;
import flow.test.serverdev.storage.ObjectStorage;

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
	 * 단건 삭제. 없으면 404 다.
	 *
	 * <p>구현은 {@link #deleteAll}에 위임한다. 지우는 방법이 둘이면 순서 계약도 둘이 되고,
	 * 한쪽만 고치는 날이 온다. 바깥에서 보이는 <b>계약만</b> 다르게 둔다 — 단건은 대상을
	 * 지목한 요청이므로 없으면 실패이고, 여러 건은 건별로 답한다.
	 */
	public void delete(UUID fileId) {
		if (!deleteAll(List.of(fileId)).notFound().isEmpty()) {
			throw new PolicyException(ErrorCode.FILE_NOT_FOUND,
				"요청하신 파일을 찾을 수 없습니다: " + fileId);
		}
	}

	/**
	 * 여러 건 삭제.
	 *
	 * <p><b>건마다 소유권을 먼저 얻는다.</b> 한 번의 {@code UPDATE ... IN (:ids)} 로 묶으면
	 * 몇 행이 바뀌었는지만 알고 <b>어느 행인지는 모른다</b>. 그러면 어떤 객체를 지워야 할지
	 * 특정할 수 없다. 왕복이 늘지만 그 수는 {@link BulkDeleteRequest#MAX_IDS} 로 묶여 있고,
	 * 대신 단건에서 검증된 순서 계약이 그대로 유지된다.
	 *
	 * <p><b>중복은 접는다.</b> 같은 id 를 두 번 보내면 두 번째는 소유권을 얻지 못하는데,
	 * 그것을 "없다" 로 답하면 한 파일이 지웠음과 없음에 <b>동시에</b> 나타난다.
	 */
	public BulkDeleteResponse deleteAll(List<UUID> fileIds) {
		List<UUID> deleted = new ArrayList<>();
		List<UUID> notFound = new ArrayList<>();

		for (UUID fileId : new LinkedHashSet<>(fileIds)) {
			if (repository.markDeleted(fileId) == 0) {
				notFound.add(fileId);
				continue;
			}
			removeClaimedObject(fileId);
			deleted.add(fileId);
		}
		return new BulkDeleteResponse(List.copyOf(deleted), List.copyOf(notFound));
	}

	/**
	 * 소유권을 얻은 뒤의 객체 제거.
	 *
	 * <p>여기서의 실패는 <b>삭제를 되돌리지 않는다</b>. 기록은 이미 삭제로 확정됐고,
	 * 그것이 사실이다 — 남는 것은 아무도 가리키지 않는 객체뿐이고 로그로 추적할 수 있다.
	 */
	private void removeClaimedObject(UUID fileId) {
		String storedKey = repository.findStoredKey(fileId).orElse(null);
		if (storedKey == null) {
			// ALLOWED 는 stored_key 가 NOT NULL 이라(ck_upload_audit_stored_key) 도달할 수 없다.
			// 그래도 조용히 넘기지 않는다 — 여기 오면 DB 제약이 무너졌다는 뜻이다.
			log.error("삭제 소유권을 얻었는데 저장 키가 없습니다. 스키마 제약을 확인해야 합니다: fileId={}",
				fileId);
			return;
		}

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
