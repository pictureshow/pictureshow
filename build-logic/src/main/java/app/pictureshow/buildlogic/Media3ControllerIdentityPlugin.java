/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Exposes the version-scoped visitor in the app plugin classloader alongside AGP. */
public class Media3ControllerIdentityPlugin implements Plugin<Project> {
  @Override
  public void apply(Project project) {
    // The app configures variant instrumentation explicitly in its androidComponents block.
  }
}
