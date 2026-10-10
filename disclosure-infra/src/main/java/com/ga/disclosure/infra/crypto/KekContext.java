package com.ga.disclosure.infra.crypto;

import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 감싸기 AAD = JCS {@code {"kekId","keyId","tenantId","v":1}} — 감싼 DEK를 다른 테넌트·키 ID·KEK로 옮기면 풀리지 않는다(운영 KMS의 암호화 컨텍스트). */
final class KekContext {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private KekContext() {
    }

    static byte[] aad(TenantId tenant, String keyId, String kekId) {
        ObjectNode aad = JSON.createObjectNode().put("kekId", kekId).put("keyId", keyId).put("tenantId", tenant.value()).put("v", 1);
        return Canonicalizer.canonicalize(aad);
    }
}
