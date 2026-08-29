package flow.test.serverdev.upload;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import flow.test.serverdev.audit.ClientAddresses;
import flow.test.serverdev.common.ApiErrorResponse;
import flow.test.serverdev.common.ErrorCode;
import flow.test.serverdev.common.PolicyException;

/**
 * 파일 업로드 API. (SPEC §7.5, §21.7)
 *
 * <p><b>{@code @RequestPart MultipartFile} 로 받지 않는다.</b> 그렇게 하면 인자 바인딩이
 * 컨트롤러 진입 <b>전에</b> 실패한다 — 파일 하나를 {@code avatar} 라는 이름으로 보내면
 * 파일은 1개지만 {@code file} 파트가 없어 {@code MissingServletRequestPartException} 으로
 * 끝나고, 우리가 정의한 코드도 감사도 나오지 않는다.
 *
 * <p>파트 판정을 <b>한곳에서</b> 한다. 0개·오명·텍스트 파트·2개 이상이 각각 어떤 코드로
 * 나가는지가 여기서 전부 결정된다.
 */
@RestController
@RequestMapping("/api/files")
public class UploadController {

	/** 파트 이름. 계약이므로 상수로 둔다. */
	static final String FILE_PART = "file";

	private final UploadService service;

	public UploadController(UploadService service) {
		this.service = service;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> upload(MultipartHttpServletRequest request) throws IOException {
		MultipartFile file = onlyFile(request);

		InetAddress clientIp = ClientAddresses.parse(request.getRemoteAddr()).orElse(null);
		UploadOutcome outcome = service.upload(file.getOriginalFilename(), file.getSize(),
			file.getInputStream(), clientIp);

		if (outcome instanceof UploadOutcome.Rejected rejected) {
			return ResponseEntity.status(rejected.code().status())
				.body(ApiErrorResponse.of(rejected.code(), message(rejected), rejected.detail()));
		}
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(UploadResponse.from((UploadOutcome.Stored) outcome));
	}

	/**
	 * 파트 판정을 <b>한곳에서</b> 한다.
	 *
	 * <p><b>파일 파트의 정의는 "제출된 파일명이 있는 파트" 다.</b> 파트 <b>이름</b>이
	 * {@code file} 인 것과 그 파트가 <b>파일</b>인 것은 별개이고, 텍스트 파트에 {@code file}
	 * 이라는 이름을 붙여 보낼 수 있다.
	 *
	 * <p>파일명이 없는 파트를 직접 걸러낸다. 파일 맵에 들어왔다는 것만으로는 파일이라고
	 * 볼 수 없다 — 실제로 파일명이 {@code null} 인 파트가 맵에 담겨 들어오고, 그대로 통과시키면
	 * <b>"파일이 필요하다" 가 아니라 "파일 이름이 잘못됐다" 로 답하게 된다.</b> 사용자가 받는
	 * 진단이 달라진다.
	 */
	private static MultipartFile onlyFile(MultipartHttpServletRequest request) {
		List<MultipartFile> files = request.getMultiFileMap().values().stream()
			.flatMap(List::stream)
			.filter(file -> file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
			.toList();

		if (files.size() > 1) {
			throw new PolicyException(ErrorCode.FILE_COUNT_EXCEEDED,
				"한 번에 파일 하나만 올릴 수 있습니다.");
		}
		return files.stream()
			.filter(file -> FILE_PART.equals(file.getName()))
			.findFirst()
			.orElseThrow(() -> new PolicyException(ErrorCode.FILE_REQUIRED,
				"'%s' 이름의 파일 파트가 필요합니다.".formatted(FILE_PART)));
	}

	private static String message(UploadOutcome.Rejected rejected) {
		Object blocked = rejected.detail().get("blockedExtension");
		return blocked instanceof String extension
			? "%s 확장자는 업로드가 차단되어 있습니다.".formatted(extension)
			: "업로드할 수 없는 파일입니다.";
	}
}
