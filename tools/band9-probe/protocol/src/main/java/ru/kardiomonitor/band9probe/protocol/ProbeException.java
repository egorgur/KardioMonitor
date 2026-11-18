package ru.kardiomonitor.band9probe.protocol;

import java.io.IOException;

/** Only fixed, non-sensitive codes may cross the protocol/report boundary. */
public final class ProbeException extends IOException {
    public final String code;
    public ProbeException(String code) { super(code); this.code = code; }
}
