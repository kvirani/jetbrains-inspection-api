# Helper script to launch the plugin in a local Rider sandbox for rapid iteration
$env:JAVA_HOME="C:\Program Files\JetBrains\JetBrains Rider 2024.3.5\jbr"
.\gradlew.bat runIde "-PlocalIdePath=C:\Program Files\JetBrains\JetBrains Rider 2024.3.5"
