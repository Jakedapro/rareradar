package dev.rareradar.core.autohunt;

public enum HuntState {
    IDLE, SCAN, SELECT_TARGET, PLAN_ROUTE, APPROACH, AIM, THROW, AWAIT_RESULT, VERIFY_CAPTURE, SUCCESS, RETRY, ABORT;

    public boolean terminal() { return this == ABORT; }
}
