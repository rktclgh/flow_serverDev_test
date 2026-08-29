package flow.test.serverdev.policy.domain;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 차단 확장자 한 건. (SPEC §3.1)
 *
 * <p><b>불변 필드를 {@code updatable = false} 로 못박았다.</b> 이름·종류·슬롯은 생성 후
 * 바뀌지 않는다. DB 에도 같은 규칙이 트리거({@code extguard_protect_fixed_row})로 들어가 있고,
 * 여기서 한 번 더 막는 이유는 <b>실수로 UPDATE 가 나가는 것을 컴파일 시점에 가깝게 잡기</b>
 * 위해서다. DB 트리거는 최후의 방어선이지 첫 방어선이 아니다.
 *
 * <p>{@code createdAt}/{@code updatedAt} 은 {@code insertable = false, updatable = false}.
 * 시각의 주인은 DB 트리거다. JPA 가 같이 쓰면 주인이 둘이 되어
 * "누가 넣은 값인가"를 추적할 수 없게 된다. 매핑만 해두는 것은
 * {@code ddl-auto=validate} 가 컬럼 존재와 타입을 확인하게 만들기 위함이다.
 *
 * <p><b>FIXED 를 만드는 팩터리가 없는 것은 의도다.</b> 고정 7행은 마이그레이션 시드가
 * 유일한 출처이며 애플리케이션은 만들지 않는다. 도메인 규칙을 타입으로 드러낸다.
 */
@Entity
@Table(name = "blocked_extension")
public class BlockedExtension {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20, updatable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private ExtensionType type;

	@Column(name = "is_blocked", nullable = false)
	private boolean blocked;

	@Column(name = "custom_slot", updatable = false)
	private Short customSlot;

	@Column(name = "created_at", insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private OffsetDateTime updatedAt;

	protected BlockedExtension() {
		// JPA
	}

	private BlockedExtension(String name, ExtensionType type, boolean blocked, Short customSlot) {
		this.name = name;
		this.type = type;
		this.blocked = blocked;
		this.customSlot = customSlot;
	}

	/**
	 * 커스텀 확장자를 만든다. {@code name} 은 {@code ExtensionNormalizer} 를 통과한 값이어야 한다.
	 *
	 * @param customSlot 1~200. 200개 상한을 선언적으로 보증하는 장치다(SPEC §3.1)
	 */
	public static BlockedExtension custom(String name, short customSlot) {
		return new BlockedExtension(name, ExtensionType.CUSTOM, true, customSlot);
	}

	/**
	 * 차단 여부를 바꾼다. <b>FIXED 만 대상이다.</b>
	 *
	 * <p>CUSTOM 은 행의 존재가 곧 차단이라 토글이라는 개념이 없다.
	 * DB 의 {@code ck_custom_always_blocked} 와 같은 규칙을 도메인에도 둔다.
	 */
	public void changeBlocked(boolean blocked) {
		if (type != ExtensionType.FIXED) {
			throw new IllegalStateException(
				"커스텀 확장자는 차단 여부를 바꿀 수 없습니다. 삭제로 해제합니다: " + name);
		}
		this.blocked = blocked;
	}

	public Long id() {
		return id;
	}

	public String name() {
		return name;
	}

	public ExtensionType type() {
		return type;
	}

	public boolean isBlocked() {
		return blocked;
	}

	public Short customSlot() {
		return customSlot;
	}

	public OffsetDateTime createdAt() {
		return createdAt;
	}

	public OffsetDateTime updatedAt() {
		return updatedAt;
	}
}
