/*
 * Copyright 2013 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asciidoc.intellij;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import org.apache.geronimo.gshell.io.SystemOutputHijacker;
import org.asciidoc.intellij.actions.AsciiDocAction;
import org.asciidoc.intellij.editor.AsciiDocPreviewEditor;
import org.asciidoc.intellij.editor.javafx.JavaFxHtmlPanelProvider;
import org.asciidoc.intellij.settings.AsciiDocApplicationSettings;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.AttributesBuilder;
import org.asciidoctor.OptionsBuilder;
import org.asciidoctor.SafeMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * @author Julien Viet
 */
public class AsciiDoc {

  private static Asciidoctor asciidoctor;

  /**
   * Base directory to look up includes.
   */
  private final File baseDir;

  /**
   * Images directory.
   */
  private final Path imagesPath;
  private final String name;

  public AsciiDoc(File baseDir, Path imagesPath, String name) {
    this.baseDir = baseDir;
    this.imagesPath = imagesPath;
    this.name = name;

    synchronized (AsciiDoc.class) {
      if (asciidoctor == null) {
        SystemOutputHijacker.install();
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        ByteArrayOutputStream boasOut = new ByteArrayOutputStream();
        ByteArrayOutputStream boasErr = new ByteArrayOutputStream();
        SystemOutputHijacker.register(new PrintStream(boasOut), new PrintStream(boasErr));
        try {
          Thread.currentThread().setContextClassLoader(AsciiDocAction.class.getClassLoader());
          asciidoctor = Asciidoctor.Factory.create();
          asciidoctor.requireLibrary("asciidoctor-diagram");
          InputStream is = this.getClass().getResourceAsStream("/sourceline-treeprocessor.rb");
          if (is == null) {
            throw new RuntimeException("unable to load script sourceline-treeprocessor.rb");
          }
          asciidoctor.rubyExtensionRegistry().loadClass(is).treeprocessor("SourceLineTreeProcessor");
        } finally {
          SystemOutputHijacker.deregister();
          notify(boasOut, boasErr);
          Thread.currentThread().setContextClassLoader(old);
        }
      }
    }
  }

  private void notify(ByteArrayOutputStream boasOut, ByteArrayOutputStream boasErr) {
    String out = boasOut.toString();
    String err = boasErr.toString();
    if (out.length() > 0) {
      Notification notification = AsciiDocPreviewEditor.NOTIFICATION_GROUP.createNotification("Message during rendering " + name, out,
        NotificationType.INFORMATION, null);
      notification.setImportant(false);
      Notifications.Bus.notify(notification);
    }
    if (err.length() > 0) {
      Notification notification = AsciiDocPreviewEditor.NOTIFICATION_GROUP.createNotification("Error during rendering " + name, err,
        NotificationType.INFORMATION, null);
      notification.setImportant(true);
      Notifications.Bus.notify(notification);
    }
  }

  public String render(String text) {
    synchronized (asciidoctor) {
      ClassLoader old = Thread.currentThread().getContextClassLoader();
      ByteArrayOutputStream boasOut = new ByteArrayOutputStream();
      ByteArrayOutputStream boasErr = new ByteArrayOutputStream();
      SystemOutputHijacker.register(new PrintStream(boasOut), new PrintStream(boasErr));
      try {
        Thread.currentThread().setContextClassLoader(AsciiDocAction.class.getClassLoader());
        return "<div id=\"content\">\n" + asciidoctor.render(text, getDefaultOptions()) + "\n</div>";
      } finally {
        SystemOutputHijacker.deregister();
        notify(boasOut, boasErr);
        Thread.currentThread().setContextClassLoader(old);
      }
    }
  }

  public Map<String, Object> getDefaultOptions() {
    Attributes attrs = AttributesBuilder.attributes().showTitle(true)
      .sourceHighlighter("coderay").attribute("coderay-css", "style")
      .attribute("env", "idea").attribute("env-idea").get();

    if (imagesPath != null) {
      final AsciiDocApplicationSettings settings = AsciiDocApplicationSettings.getInstance();
      if (settings.getAsciiDocPreviewSettings().getHtmlPanelProviderInfo().getClassName().equals(JavaFxHtmlPanelProvider.class.getName())) {
        attrs.setAttribute("outdir", imagesPath.toAbsolutePath().normalize().toString());
      }
    }
    OptionsBuilder opts = OptionsBuilder.options().safe(SafeMode.UNSAFE).backend("html5").headerFooter(false).attributes(attrs).option("sourcemap", "true")
      .baseDir(baseDir);
    return opts.asMap();
  }
}
