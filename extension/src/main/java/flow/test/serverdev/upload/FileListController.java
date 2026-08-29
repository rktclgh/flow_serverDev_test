package flow.test.serverdev.upload;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 업로드된 파일의 목록. (GET /api/files)
 */
@RestController
@RequestMapping("/api/files")
public class FileListController {

	private final FileListService service;

	public FileListController(FileListService service) {
		this.service = service;
	}

	@GetMapping
	public FileListResponse list() {
		return new FileListResponse(service.list());
	}
}
