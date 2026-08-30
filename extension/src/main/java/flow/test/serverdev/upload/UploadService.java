package flow.test.serverdev.upload;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.List;

import org.springframework.stereotype.Service;

import flow.test.serverdev.audit.UploadAttempt;
import flow.test.serverdev.audit.UploadAuditRecorder;
import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.storage.ObjectStorage;
import flow.test.serverdev.storage.StorageException;
import flow.test.serverdev.storage.StorageKey;
import flow.test.serverdev.storage.StorageKeyGenerator;
import flow.test.serverdev.storage.StorageOutcomeUnknownException;

/**
 * 업로드 처리. (SPEC §21.6)
 *
 * <p><b>이 클래스에 {@code @Transactional} 을 걸지 않는다.</b> 감사 기록기가
 * {@code REQUIRES_NEW} 로 도는데 바깥 트랜잭션이 커넥션을 잡고 있으면 매 업로드가 커넥션을
 * 두 개씩 쓰게 되고, 동시 업로드에서 풀이 고갈된다. 저장(PUT)이 트랜잭션 밖이어야 하는 이유도
 * 같다 — 외부 I/O 를 DB 커넥션이 기다리게 만들면 안 된다.
 *
 * <p><b>핵심 규칙: 결과가 불확실하면 아무것도 확정하지 않는다.</b> {@code PENDING} 은
 * "실패" 가 아니라 "모른다" 의 표현이고, 스위퍼가 임계 시간 뒤에 정리한다.
 */
@Service
public class UploadService {

	/** 시그니처 검사용 선두 바이트를 읽고 되감을 수 있을 만큼. */
	static final int PREFIX_BUFFER = 8192;

	private final UploadValidator validator;
	private final StorageKeyGenerator keyGenerator;
	private final ObjectStorage storage;
	private final UploadAuditRecorder audit;

	public UploadService(UploadValidator validator, StorageKeyGenerator keyGenerator,
			ObjectStorage storage, UploadAuditRecorder audit) {
		this.validator = validator;
		this.keyGenerator = keyGenerator;
		this.storage = storage;
		this.audit = audit;
	}

	public UploadOutcome upload(String rawFilename, long size, InputStream content, InetAddress clientIp) {
		try (BufferedInputStream in = new BufferedInputStream(content, PREFIX_BUFFER)) {
			byte[] prefix = readPrefix(in);
			UploadDecision decision = validator.validate(rawFilename, size, prefix);

			if (decision instanceof UploadDecision.Rejected rejected) {
				audit.recordBlocked(attempt(rawFilename, clientIp, size, rejected), rejected.code().name());
				return new UploadOutcome.Rejected(rejected.code(), rejected.detail());
			}
			return store((UploadDecision.Accepted) decision, rawFilename, size, in, clientIp);
		}
		catch (IOException e) {
			// 스토리지에 손대기 전이므로 객체가 없는 것이 확실하다.
			throw new StorageException("업로드 스트림을 읽지 못했습니다: " + rawFilename, e);
		}
	}

	/**
	 * 선두 바이트를 읽고 <b>되감는다.</b> {@code getInputStream()} 을 두 번 여는 것이 되는지는
	 * 구현에 달렸으므로 거기 기대지 않는다 — 버퍼 안에서의 {@code mark}/{@code reset} 은
	 * 계약으로 보장된다.
	 */
	private static byte[] readPrefix(BufferedInputStream in) throws IOException {
		in.mark(SignatureInspector.PREFIX_LENGTH);
		byte[] prefix = in.readNBytes(SignatureInspector.PREFIX_LENGTH);
		in.reset();
		return prefix;
	}

	private UploadOutcome store(UploadDecision.Accepted accepted, String rawFilename, long size,
			InputStream content, InetAddress clientIp) {
		StorageKey key = keyGenerator.generate();
		UploadAttempt attempt = new UploadAttempt(rawFilename, clientIp, size,
			accepted.extension().orElse(null), accepted.note());

		// ① 자리를 먼저 잡는다. 여기서 실패하면 스토리지는 손도 대지 않은 상태다.
		long auditId = audit.beginPending(attempt, key);

		try {
			storage.store(key, content, size);
		}
		catch (StorageOutcomeUnknownException e) {
			// 저장됐는지 모른다. ERROR 로 확정하면 실제 객체가 정리 대상에서 사라진다.
			throw e;
		}
		catch (StorageException e) {
			// 서버가 거부했다. 객체가 없는 것이 확실하므로 확정해도 된다.
			audit.markError(auditId, ErrorCode.STORAGE_UNAVAILABLE.name());
			throw e;
		}

		// ② 확정. 실패해도 객체를 지우지 않는다 — 커밋이 실제로는 성공했을 수 있다.
		audit.markAllowed(auditId);
		return new UploadOutcome.Stored(key.fileId(), rawFilename, size);
	}

	private static UploadAttempt attempt(String rawFilename, InetAddress clientIp, long size,
			UploadDecision.Rejected rejected) {
		Object blocked = rejected.detail().get("blockedExtension");
		return new UploadAttempt(rawFilename, clientIp, size,
			blocked instanceof String extension ? extension : null, null);
	}

}
