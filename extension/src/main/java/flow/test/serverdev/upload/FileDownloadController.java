package flow.test.serverdev.upload;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 파일 다운로드 API. (SPEC §7.6)
 *
 * <p>편의 기능이 아니라 <b>실증</b>이다. §9 의 저장 규칙 — 키에 파일명을 쓰지 않는다,
 * Content-Type 을 {@code octet-stream} 으로 강제한다 — 은 문서에만 있으면 지켜지는지 알 수
 * 없다. 올린 것을 도로 받아보는 경로가 있어야 그것이 코드로 확인된다.
 *
 * <p>{@code fileId} 는 UUID 라 <b>순차 열거가 불가능</b>하다. 순차 id 를 노출했다면 이
 * 엔드포인트 하나로 남이 올린 파일을 전부 훑을 수 있다.
 */
@RestController
@RequestMapping("/api/files")
public class FileDownloadController {

	/** {@code HttpHeaders} 에 상수가 없는 헤더라 이름을 여기에 둔다. */
	private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

	private final FileDownloadService service;

	public FileDownloadController(FileDownloadService service) {
		this.service = service;
	}

	/**
	 * 헤더 셋이 함께여야 방어가 성립한다.
	 *
	 * <ul>
	 *   <li>{@code application/octet-stream} — 저장 시점에 강제한 값과 같다. 클라이언트가 보낸
	 *       Content-Type 은 애초에 어디에도 저장하지 않는다</li>
	 *   <li>{@code attachment} — 탭에서 그대로 열리지 않는다. HTML 이 올라와도 실행 문맥이 없다</li>
	 *   <li>{@code nosniff} — 없으면 브라우저가 <b>내용을 보고 스스로 타입을 정한다.</b>
	 *       그러면 octet-stream 을 붙인 의미가 사라진다</li>
	 * </ul>
	 *
	 * <p>파일명은 RFC 5987({@code filename*=UTF-8''...})로 싣는다. 헤더는 ASCII 만 실을 수
	 * 있어 한글을 그대로 넣으면 각 계층이 다른 인코딩으로 읽어 이름이 깨진다.
	 * {@link ContentDisposition} 이 UTF-8 charset 을 주면 그 형식으로만 직렬화한다.
	 *
	 * <p><b>{@code nosniff} 는 {@code SecurityConfig} 도 모든 응답에 붙인다.</b> 그래서 이 줄만
	 * 지워도 테스트가 죽지 않는다 — 실측으로 확인했다. 그래도 남겨두는 이유는 죽지 않는 이유가
	 * "이 줄이 무의미해서" 가 아니라 <b>"다른 방어가 같은 것을 막고 있어서"</b> 이기 때문이다.
	 * 이 엔드포인트에서 그 헤더는 전역 정책이 아니라 <b>계약의 일부</b>다. 겹친 방어 중 하나를
	 * 관측 불가라는 이유로 걷어내면 남은 하나가 무너질 때 아무것도 남지 않는다
	 * ({@code MinioObjectStorage.FORCED_CONTENT_TYPE} 과 같은 판단이다).
	 * 둘 다 없애면 다운로드 테스트가 실패한다 — 그것도 실측했다.
	 *
	 * <p>{@link InputStreamResource} 를 쓰면 스프링이 스트림을 응답으로 흘려보내고 닫는다.
	 * 바이트 배열로 읽어 담지 않는 이유는 10MB 파일이 동시에 여러 개 오면 그만큼 힙에 쌓이기
	 * 때문이다.
	 */
	@GetMapping("/{fileId}/content")
	public ResponseEntity<Resource> download(@PathVariable UUID fileId) {
		StoredFile file = service.load(fileId);

		ContentDisposition disposition = ContentDisposition.attachment()
			.filename(file.filename(), StandardCharsets.UTF_8)
			.build();

		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.header(CONTENT_TYPE_OPTIONS, "nosniff")
			.body(new InputStreamResource(file.content()));
	}
}
