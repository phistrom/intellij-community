// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.status.widget;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@ApiStatus.Internal
public final class StatusBarWidgetProviderToFactoryAdapter implements StatusBarWidgetFactory {
  private final Project myProject;
  @SuppressWarnings("deprecation") private final StatusBarWidgetProvider myProvider;

  private boolean widgetWasCreated;
  private @Nullable StatusBarWidget myWidget;

  public StatusBarWidgetProviderToFactoryAdapter(@NotNull Project project, @SuppressWarnings("deprecation") @NotNull StatusBarWidgetProvider provider) {
    myProject = project;
    myProvider = provider;
  }

  @Override
  public @NotNull String getId() {
    StatusBarWidget widget = getWidget();
    return widget != null ? widget.ID() : myProvider.getClass().getName();
  }

  @Override
  public @NotNull String getDisplayName() {
    StatusBarWidget widget = getWidget();
    if (widget != null) {
      StatusBarWidget.WidgetPresentation presentation = widget.getPresentation();
      String result = presentation != null ? StringUtil.notNullize(presentation.getTooltipText()) : "";
      if (!result.isEmpty()) {
        return result;
      }
      if (ApplicationManager.getApplication().isInternal()) {
        //noinspection HardCodedStringLiteral
        return widget.ID();
      }
    }
    return "";
  }

  @Override
  public boolean isAvailable(@NotNull Project project) {
    IdeFrame frame = WindowManager.getInstance().getIdeFrame(myProject);
    return frame != null && myProvider.isCompatibleWith(frame) && getWidget() != null;
  }

  @Override
  public boolean isConfigurable() {
    return !getDisplayName().isEmpty();
  }

  @Override
  public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
    return true;
  }

  @Override
  public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
    return Objects.requireNonNull(getWidget());
  }

  private @Nullable StatusBarWidget getWidget() {
    if (!widgetWasCreated) {
      myWidget = myProvider.getWidget(myProject);
      widgetWasCreated = true;
    }
    return myWidget;
  }

  @Override
  public void disposeWidget(@NotNull StatusBarWidget widget) {
    myWidget = null;
    widgetWasCreated = false;
    Disposer.dispose(widget);
  }

  public @NotNull String getAnchor() {
    return myProvider.getAnchor();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    StatusBarWidgetProviderToFactoryAdapter adapter = (StatusBarWidgetProviderToFactoryAdapter)o;
    return myProvider.equals(adapter.myProvider) && myProject.equals(adapter.myProject);
  }

  @Override
  public int hashCode() {
    return Objects.hash(myProvider, myProject);
  }
}
