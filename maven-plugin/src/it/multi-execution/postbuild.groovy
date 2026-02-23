File signedExe = new File(basedir, 'target/classes/app.exe')
assert signedExe.exists(), "Signed EXE does not exist: ${signedExe}"
assert signedExe.text == 'signed-exe-content', "Expected 'signed-exe-content' but got: '${signedExe.text}'"

File signedJar = new File(basedir, 'target/multi-execution-1.0.0.jar')
assert signedJar.exists(), "Signed JAR does not exist: ${signedJar}"
assert signedJar.text == 'signed-jar-content', "Expected 'signed-jar-content' but got: '${signedJar.text}'"
