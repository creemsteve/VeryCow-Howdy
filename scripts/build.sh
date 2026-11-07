#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p build/classes build/app
find src/main/java -name '*.java' > build/sources.txt
javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp 'lib/*' -d build/classes @build/sources.txt
cp src/main/resources/* build/classes/
cp voice/dac_worker.py build/classes/
jar --create --file build/app/vch.jar -C build/classes .
cp lib/*.jar build/app/
if [ -f build/models/dac.json ]; then mkdir -p build/app/models; cp build/models/dac*.onnx build/models/dac.json build/app/models/; fi
echo 'Run: java --add-modules jdk.httpserver -cp "build/app/*" chat.vch.ClientApp'
