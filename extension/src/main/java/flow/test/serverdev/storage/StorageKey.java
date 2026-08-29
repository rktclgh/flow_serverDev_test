package flow.test.serverdev.storage;

import java.util.UUID;

/**
 * MinIO 에 저장된 객체를 가리키는 키. (SPEC §9)
 *
 * <p>키에는 <b>원본 파일명이 한 글자도 들어가지 않는다.</b> 이름을 경로 재료로 쓰지 않으면
 * 경로 조작({@code ../../etc/passwd})은 성립 자체가 불가능하다. 걸러내는 방식이 아니라
 * 재료를 주지 않는 방식이며, 필터는 우회당할 수 있지만 이쪽은 우회할 대상이 없다.
 *
 * <p>{@code fileId} 를 {@code value} 와 <b>따로 들고 있는 이유</b>는 다운로드가
 * {@code GET /api/files/&#123;fileId&#125;/content} 이기 때문이다(SPEC §7.6).
 * 키 문자열에서 UUID 를 다시 파싱해 조회하면 {@code stored_key LIKE '%/' || :uuid} 가 되어
 * 인덱스를 타지 못한다. 애초에 값을 갈라 두면 그 문제가 생기지 않는다.
 *
 * @param fileId 클라이언트에 노출되는 식별자. 순차 id 가 아니므로 열거할 수 없다
 * @param value  실제 저장 키. {@code yyyy/MM/dd/&#123;fileId&#125;}
 */
public record StorageKey(UUID fileId, String value) {
}
