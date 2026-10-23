/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.buildlogic;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A version-scoped workaround for androidx/media#3214, without replacing the session implementation. */
public abstract class Media3ControllerIdentityFix
    implements AsmClassVisitorFactory<InstrumentationParameters.None> {
  private static final String COMPAT =
      "androidx.media3.session.legacy.MediaSessionCompat$MediaSessionImplApi28";
  private static final String CONTROLLER = "androidx.media3.session.MediaSession$ControllerInfo";
  private static final String HELPER = "app/pictureshow/LegacyControllerIdentity";
  private static final String PLATFORM_INFO =
      "android/media/session/MediaSessionManager$RemoteUserInfo";
  private static final String COMPAT_INFO =
      "androidx/media3/session/legacy/MediaSessionManager$RemoteUserInfo";

  @Override
  public boolean isInstrumentable(ClassData data) {
    return data.getClassName().equals(COMPAT) || data.getClassName().equals(CONTROLLER);
  }

  @Override
  public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
    boolean compat = context.getCurrentClassData().getClassName().equals(COMPAT);
    return new ClassVisitor(Opcodes.ASM9, next) {
      private int replacements;

      @Override
      public MethodVisitor visitMethod(
          int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM9, delegate) {
          @Override
          public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
            super.visitMethodInsn(opcode, owner, method, desc, itf);
            if (compat && name.equals("getCurrentControllerInfo")
                && descriptor.equals("()L" + COMPAT_INFO + ";")
                && owner.equals("android/media/session/MediaSession")
                && method.equals("getCurrentControllerInfo")
                && desc.equals("()L" + PLATFORM_INFO + ";")) {
              super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER + "$Api28", "normalize",
                  "(L" + PLATFORM_INFO + ";)L" + PLATFORM_INFO + ";", false);
              replacements++;
            }
          }

          @Override
          public void visitFieldInsn(int opcode, String owner, String field, String desc) {
            if (!compat && name.equals("<init>") && opcode == Opcodes.PUTFIELD
                && owner.equals(CONTROLLER.replace('.', '/'))
                && field.equals("isPackageNameVerified") && desc.equals("Z")) {
              // Stack: this, verified. Do not advertise the unknown-controller placeholder as verified.
              super.visitVarInsn(Opcodes.ALOAD, 0);
              super.visitFieldInsn(Opcodes.GETFIELD, owner, "remoteUserInfo", "L" + COMPAT_INFO + ";");
              super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COMPAT_INFO, "getPackageName", "()Ljava/lang/String;", false);
              super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "verified", "(ZLjava/lang/String;)Z", false);
              replacements++;
            }
            super.visitFieldInsn(opcode, owner, field, desc);
          }
        };
      }

      @Override
      public void visitEnd() {
        if (replacements != 1) {
          throw new IllegalStateException("Media3 identity workaround expected one patch in "
              + context.getCurrentClassData().getClassName() + ", found " + replacements
              + "; review the workaround against the dependency source");
        }
        super.visitEnd();
      }
    };
  }
}
