package com.truthscope.web.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;

/**
 * R2-1/CX-16: DnsResolver는 functional interface 아님 (resolve + resolveCanonicalHostname). SsrfGuard로
 * 검증된 host + addresses만 허용하는 pinned resolver. ContentExtractService.buildClient()에서 per-request
 * 인스턴스로 주입.
 */
public final class PinnedDnsResolver implements DnsResolver {
  private final InetAddress[] pinned;
  private final String approvedHost;

  /**
   * SsrfGuard에서 검증된 DNS 결과로 resolver 생성.
   *
   * @throws NullPointerException pinned 또는 approvedHost가 null인 경우 (defense-in-depth fail-fast)
   */
  public PinnedDnsResolver(InetAddress[] pinned, String approvedHost) {
    // SpotBugs EI_EXPOSE_REP2 회피 + 외부 mutation 방어 + null fail-fast
    this.pinned = Objects.requireNonNull(pinned, "pinned must not be null").clone();
    this.approvedHost = Objects.requireNonNull(approvedHost, "approvedHost must not be null");
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    if (host.equalsIgnoreCase(approvedHost)) {
      return pinned.clone(); // SpotBugs EI_EXPOSE_REP 회피
    }
    throw new UnknownHostException("Unauthorized host (SSRF guard): " + host);
  }

  @Override
  public String resolveCanonicalHostname(String host) throws UnknownHostException {
    if (host.equalsIgnoreCase(approvedHost)) {
      return approvedHost;
    }
    throw new UnknownHostException("Unauthorized host (SSRF guard): " + host);
  }
}
