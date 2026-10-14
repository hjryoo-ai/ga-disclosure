package com.ga.disclosure.workflow.secret;

/** 이름의 비밀이 출처에 없다. 문장은 이름뿐이다. */
public final class SecretMissingException extends IllegalStateException {

    private final SecretName name;

    public SecretMissingException(SecretName name) {
        super("secret " + name + " is not available");
        this.name = name;
    }

    public SecretName name() {
        return name;
    }
}
