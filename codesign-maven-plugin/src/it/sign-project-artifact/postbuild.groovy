File signedJar = new File(basedir, 'target/sign-project-artifact-1.0.0.jar')
assert signedJar.exists(), "Signed JAR does not exist: ${signedJar}"
assert signedJar.text == 'signed-content', "Expected 'signed-content' but got: '${signedJar.text}'"
