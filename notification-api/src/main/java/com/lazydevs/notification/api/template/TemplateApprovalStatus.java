package com.lazydevs.notification.api.template;

/**
 * Approval of a template at the provider, for channels that need one, such as WhatsApp
 * (Meta template review) or SMS in India (DLT registration) (DD-38).
 *
 * <p>{@code null} on a version means the deployment does not track approval.
 *
 * @since 1.3.0
 */
public enum TemplateApprovalStatus {
    NOT_SUBMITTED,
    SUBMITTED,
    APPROVED,
    REJECTED,
    PAUSED,
    DISABLED
}
