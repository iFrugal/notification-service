package com.lazydevs.notification.core.template.admin;

import com.lazydevs.notification.api.template.TemplateVersion;

import java.util.List;

/**
 * One template of the admin listing, seen from the default layer (DD-38). Versions carry no
 * content.
 *
 * @param channel             channel, lower case
 * @param templateId          template id
 * @param builtIn             whether a default built-in file exists
 * @param builtInTenants      tenants with their own built-in file
 * @param defaultOverride     the default layer's published override, or {@code null}
 * @param defaultDraft        the default layer's draft, or {@code null}
 * @param tenantOverrideCount tenants with a published override
 * @param tenantDraftCount    tenants with a draft
 * @param editable            whether the channel is editable in this deployment
 * @since 1.3.0
 */
public record TemplateListItem(String channel, String templateId, boolean builtIn, List<String> builtInTenants,
                               TemplateVersion defaultOverride, TemplateVersion defaultDraft,
                               int tenantOverrideCount, int tenantDraftCount, boolean editable) {
}
