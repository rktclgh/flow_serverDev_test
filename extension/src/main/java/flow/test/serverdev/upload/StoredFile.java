package flow.test.serverdev.upload;

import java.io.InputStream;

/**
 * 내보낼 파일 하나. (SPEC §7.6)
 *
 * @param filename <b>이스케이프된</b> 파일명이다. 기록에 남은 값을 그대로 쓴다 —
 *                 원본을 따로 보관하지 않는 이유는 {@link flow.test.serverdev.audit.AuditFilenames}
 *                 가 손대는 것이 {@code Cc}/{@code Cf} 뿐이라 한글·이모지·공백은 살아남고,
 *                 걸리는 것은 RTL 재정의처럼 <b>헤더로 나가면 그 자체가 스푸핑</b>인 문자뿐이기 때문이다
 * @param content  호출자가 닫는다. 응답으로 흘려보내므로 서비스가 먼저 닫으면 안 된다
 */
public record StoredFile(String filename, InputStream content) {
}
