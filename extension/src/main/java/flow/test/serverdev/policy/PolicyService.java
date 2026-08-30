package flow.test.serverdev.policy;

import java.util.Comparator;
import java.util.List;
import java.net.InetAddress;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;
import flow.test.serverdev.policy.domain.BlockedExtension;
import flow.test.serverdev.policy.domain.ExtensionType;
import flow.test.serverdev.policy.dto.PolicyResponse;

/**
 * 확장자 차단 정책의 도메인 규칙. (SPEC §7.1~7.4, §8.1)
 *
 * <p><b>모든 입력은 {@link ExtensionNormalizer} 를 통과한다.</b> 경로 변수도 예외가 아니다.
 * 정책에 저장되는 문자열과 업로드 검증에서 추출되는 문자열이 같은 규칙으로 만들어져야
 * 그 사이에 틈이 생기지 않는다.
 *
 * <p><b>정규화 실패의 처리가 API 마다 다른 것은 의도다.</b>
 * 커스텀 추가는 사용자가 입력한 <i>값</i>이므로 400(형식 오류)이지만,
 * 토글·삭제의 이름은 <i>리소스를 지목하는 경로</i>이므로 404(그런 리소스 없음)다.
 * "정규화되지 않는 이름"은 애초에 저장될 수 없으므로 그런 리소스는 존재할 수 없다.
 */
@Service
public class PolicyService {

	/** 과제 명시 상한. DB 의 {@code ck_custom_slot} 과 같은 값이어야 한다. */
	public static final int CUSTOM_LIMIT = 200;

	/** 요구사항이 지정한 화면 순서. 이름순도 id순도 아니므로 코드에 명시한다. */
	private static final List<String> FIXED_ORDER =
		List.of("bat", "cmd", "com", "cpl", "exe", "scr", "js");

	/**
	 * 슬롯 경합 재시도 횟수. 제약 위반이 발생하면 그 트랜잭션은 Postgres 에서 이미 중단되므로
	 * <b>같은 트랜잭션 안에서는 재시도할 수 없다</b>. 그래서 트랜잭션을 감싸는 바깥에서 돈다.
	 */
	private static final int SLOT_RETRY = 3;

	private static final String NAME_CONSTRAINT = "uq_blocked_extension_name";

	private static final String SLOT_CONSTRAINT = "uq_blocked_extension_slot";

	private final BlockedExtensionRepository repository;
	private final ExtensionNormalizer normalizer;
	private final TransactionTemplate transactionTemplate;

	private final PolicyChangeRecorder changeRecorder;

	public PolicyService(BlockedExtensionRepository repository, ExtensionNormalizer normalizer,
			TransactionTemplate transactionTemplate,
			PolicyChangeRecorder changeRecorder) {
		this.changeRecorder = changeRecorder;
		this.repository = repository;
		this.normalizer = normalizer;
		this.transactionTemplate = transactionTemplate;
	}

	@Transactional(readOnly = true)
	public PolicyResponse getPolicy() {
		List<BlockedExtension> all = repository.findAll();

		List<PolicyResponse.FixedItem> fixed = all.stream()
			.filter(extension -> extension.type() == ExtensionType.FIXED)
			.sorted(Comparator.comparingInt(extension -> FIXED_ORDER.indexOf(extension.name())))
			.map(extension -> new PolicyResponse.FixedItem(extension.name(), extension.isBlocked()))
			.toList();

		List<PolicyResponse.CustomItem> custom = all.stream()
			.filter(extension -> extension.type() == ExtensionType.CUSTOM)
			.map(BlockedExtension::name)
			.sorted()
			.map(PolicyResponse.CustomItem::new)
			.toList();

		return new PolicyResponse(fixed, custom, custom.size(), CUSTOM_LIMIT);
	}

	/**
	 * 고정 확장자의 차단 여부를 바꾼다. <b>멱등</b> — 같은 값을 다시 보내도 성공이다.
	 *
	 * <p>동시 요청은 마지막 요청이 이긴다(last-write-wins). 단일 관리자 전제이고
	 * 토글은 멱등이라 실해가 없다. 화면은 응답 후 목록을 재조회해 서버 상태와 맞춘다.
	 */
	@Transactional
	public PolicyResponse.FixedItem toggleFixed(String rawName, boolean blocked,
			InetAddress clientIp) {
		String name = normalizeForLookup(rawName);

		BlockedExtension extension = repository.findByName(name)
			.filter(found -> found.type() == ExtensionType.FIXED)
			.orElseThrow(() -> notFound(name));

		// 바뀌기 전 상태를 먼저 붙잡는다. 바꾼 뒤에는 읽을 수 없다.
		boolean before = extension.isBlocked();
		extension.changeBlocked(blocked);
		changeRecorder.toggled(name, before, blocked, clientIp);

		return new PolicyResponse.FixedItem(extension.name(), extension.isBlocked());
	}

	/**
	 * 커스텀 확장자를 추가한다.
	 *
	 * <p><b>이 메서드에 {@code @Transactional} 이 없는 것은 의도다.</b> 슬롯 경합 재시도가
	 * 새 트랜잭션을 요구하기 때문이다(위 {@link #SLOT_RETRY} 참조). 실제 쓰기는
	 * {@code transactionTemplate} 안에서 일어난다.
	 */
	public PolicyResponse.CustomItem addCustom(String rawName, InetAddress clientIp) {
		// 정규화는 DB 를 건드릴 이유가 없으므로 트랜잭션 밖에서 끝낸다.
		// 형식 오류 하나 때문에 커넥션을 잡고 잠금을 거는 것은 낭비다.
		String name = normalizeForCreate(rawName);

		for (int attempt = 1; attempt <= SLOT_RETRY; attempt++) {
			try {
				return transactionTemplate.execute(status -> insertCustom(name, clientIp));
			}
			catch (DataIntegrityViolationException exception) {
				// 이름 충돌은 재시도해도 결과가 같다 — 즉시 판정한다.
				if (ConstraintViolations.involves(exception, NAME_CONSTRAINT)) {
					// 롤백된 뒤라 어떤 종류와 부딪혔는지 다시 확인해야 한다.
					boolean fixed = repository.findByName(name)
						.map(found -> found.type() == ExtensionType.FIXED)
						.orElse(false);
					throw conflictFor(name, fixed);
				}

				// 슬롯 충돌이 아니라면 재시도해도 같은 결과다.
				// 예컨대 정규화에 버그가 생겨 ck_blocked_extension_format 이 거부한 경우,
				// 재시도는 트랜잭션만 세 번 낭비하고 끝내 EXT_SLOT_CONFLICT 라는
				// 사실과 다른 코드를 내보낸다. 모르는 위반은 그대로 올려보내
				// 500 과 로그로 드러나게 한다 — 조용히 409 로 바꾸는 것보다 낫다.
				if (!ConstraintViolations.involves(exception, SLOT_CONSTRAINT)) {
					throw exception;
				}
			}
		}

		throw new PolicyException(ErrorCode.EXT_SLOT_CONFLICT,
			"다른 요청과 겹쳐 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.");
	}

	/**
	 * 커스텀 확장자를 삭제하고 <b>실제로 지워진 정규화된 이름</b>을 돌려준다.
	 *
	 * <p>반환값이 필요한 이유는 감사 기록 때문이다. 호출자가 입력값을 그대로 기록하면
	 * 정규화가 개입한 요청에서 기록과 저장소가 어긋난다 — {@code .SH} 를 지웠다고 적히지만
	 * 실제로 사라진 것은 {@code sh} 다. 기록이 사실과 다르면 감사 기록의 의미가 없다.
	 */
	@Transactional
	public PolicyResponse.CustomItem deleteCustom(String rawName, InetAddress clientIp) {
		String name = normalizeForLookup(rawName);

		ExtensionType type = repository.findTypeByName(name).orElseThrow(() -> notFound(name));

		if (type == ExtensionType.FIXED) {
			throw new PolicyException(ErrorCode.EXT_FIXED_NOT_DELETABLE,
				"%s는 고정 확장자라 삭제할 수 없습니다. 체크를 해제하세요.".formatted(name),
				Map.of("extension", name, "policyType", ExtensionType.FIXED.name()));
		}

		if (repository.deleteByNameAndType(name, ExtensionType.CUSTOM) == 0) {
			// 조회와 삭제 사이에 다른 요청이 먼저 지웠다.
			// 호출자가 원한 결과 상태("없음")는 이미 이뤄졌지만, 이 요청이 지운 것은 아니다.
			// 404 로 알리는 편이 정직하다 — 지우지 않았는데 204 를 주면 감사 로그가 거짓이 된다.
			throw notFound(name);
		}

		changeRecorder.customDeleted(name, clientIp);
		return new PolicyResponse.CustomItem(name);
	}

	// --- 내부 ---------------------------------------------------------------

	private PolicyResponse.CustomItem insertCustom(String name, InetAddress clientIp) {
		// 잠금을 먼저 잡아야 "빈 슬롯 조회 → INSERT" 사이에 다른 요청이 끼어들지 못한다.
		repository.lockPolicy();

		repository.findByName(name).ifPresent(existing -> {
			throw conflictFor(existing.name(), existing.type() == ExtensionType.FIXED);
		});

		short slot = repository.findFirstFreeSlot(CUSTOM_LIMIT)
			.orElseThrow(() -> new PolicyException(ErrorCode.EXT_LIMIT_EXCEEDED,
				"커스텀 확장자는 최대 %d개까지 등록할 수 있습니다.".formatted(CUSTOM_LIMIT),
				Map.of("customLimit", CUSTOM_LIMIT)));

		// saveAndFlush 여야 한다. save 만 하면 제약 위반이 커밋 시점에 터져
		// 이 메서드 밖에서 발생하고, 아래의 사유별 판정이 무력화된다.
		BlockedExtension saved = repository.saveAndFlush(BlockedExtension.custom(name, slot));
		changeRecorder.customAdded(saved.name(), clientIp);
		return new PolicyResponse.CustomItem(saved.name());
	}

	/**
	 * 이름이 이미 있을 때의 판정. <b>고정과 겹친 경우를 구분해 어디서 처리할지 안내한다</b>
	 * — 과제 3-2 "고정과 커스텀이 겹칠 때"에 대한 답이다.
	 */
	private PolicyException conflictFor(String name, boolean fixed) {
		if (fixed) {
			return new PolicyException(ErrorCode.EXT_FIXED_CONFLICT,
				"%s는 고정 확장자입니다. 고정 확장자 영역에서 체크하세요.".formatted(name),
				Map.of("extension", name, "policyType", ExtensionType.FIXED.name()));
		}
		return new PolicyException(ErrorCode.EXT_DUPLICATE,
			"%s는 이미 등록되어 있습니다.".formatted(name),
			Map.of("extension", name, "policyType", ExtensionType.CUSTOM.name()));
	}

	private PolicyException notFound(String name) {
		return new PolicyException(ErrorCode.EXT_NOT_FOUND, "등록되지 않은 확장자입니다.",
			Map.of("extension", name));
	}

	/** 사용자가 <b>입력한 값</b>의 정규화. 실패 사유를 그대로 사용자에게 알린다. */
	private String normalizeForCreate(String raw) {
		NormalizeResult result = normalizer.normalize(raw);

		if (result instanceof NormalizeResult.Ok ok) {
			return ok.value();
		}

		RejectReason reason = ((NormalizeResult.Rejected) result).reason();
		if (reason == RejectReason.TOO_LONG) {
			throw new PolicyException(ErrorCode.EXT_TOO_LONG,
				"확장자는 20자를 넘을 수 없습니다.");
		}
		throw new PolicyException(ErrorCode.EXT_INVALID_FORMAT,
			"확장자는 영문 소문자와 숫자만 사용할 수 있습니다.");
	}

	/**
	 * <b>리소스를 지목하는 이름</b>의 정규화. 실패하면 404다.
	 *
	 * <p>입력값을 응답 메시지에 되돌려 담지 않는다. 정규화를 통과한 이름은
	 * {@code ^[a-z0-9]{1,20}$} 라 안전하지만, 통과하지 못한 원본은 무엇이든 될 수 있다.
	 */
	private String normalizeForLookup(String raw) {
		NormalizeResult result = normalizer.normalize(raw);

		if (result instanceof NormalizeResult.Ok ok) {
			return ok.value();
		}
		throw new PolicyException(ErrorCode.EXT_NOT_FOUND, "등록되지 않은 확장자입니다.");
	}
}
