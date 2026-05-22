package com.github.wooju.oracleinspector.ui.sessions;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Bridge to {@link OracleSessionsPanel}.
 *
 * <p>Implemented in Java on purpose: Kotlin generates synthetic overrides for
 * every default method in {@link ToolWindowFactory} (getAnchor / getIcon /
 * manage / isApplicable / isDoNotActivateOnStart), which the JetBrains Plugin
 * Verifier flags as overrides of internal / experimental API and which
 * Marketplace moderation rejects. Plain Java leaves the defaults alone.
 */
public final class OracleSessionsToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        OracleSessionsPanel panel = new OracleSessionsPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
