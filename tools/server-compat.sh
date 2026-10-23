#!/bin/sh
# Run the exported JVM checker without Gradle/Android SDK on the server host.
set -eu
: "${PS_COMPAT_URL:?Set PS_COMPAT_URL to the disposable test server}"
: "${PS_COMPAT_VERSION:?Set PS_COMPAT_VERSION to its exact reported version}"
RUNTIME=${PS_COMPAT_RUNTIME:-core/build/reports/server-compat/runtime}
if [ ! -d "$RUNTIME/classes" ]; then
    echo 'Build the runner first: ./gradlew :core:serverCompatRuntime' >&2
    exit 2
fi
exec java -Xmx512m -cp "$RUNTIME/classes:$RUNTIME/lib/*" app.pictureshow.core.ServerCompatibilityKt
