package com.example.collections.model;

public class AccountDayView {
    private AccountRecord account;
    private ChangeType changeType;

    public AccountDayView() { }

    public AccountDayView(AccountRecord account, ChangeType changeType) {
        this.account = account;
        this.changeType = changeType;
    }

    public AccountRecord getAccount() { return account; }
    public void setAccount(AccountRecord account) { this.account = account; }
    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public enum ChangeType { UNCHANGED, NEW, MISSING, CHANGED }
}
