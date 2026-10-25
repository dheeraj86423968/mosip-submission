These sources reference io.mosip.registration.* (registration-services / registration-client 1.2.0.x) and are NOT
compiled by integration-desktop's default build. They are copied into registration-client/src/main/java and applied
together with src/mosip/patches/*.patch.

Status (2026-10-24): COMPILED with javac against mosip/registration-client release-1.2.0.x @ c1cc4e7f, all seven
patches applied (registration-services with JDK 11; registration-client with JDK 21 javac --release 11, because JDK 11
javac cannot read the Java 17 mosip-vivid jars). Never run: the client JRE is Zulu 11, which cannot load the Java 17
mosip-vivid jars. Rerun: ../../../../../../compile-against-client.sh <work-dir>.
Details: docs/testing/desktop-mosip-compile.md
