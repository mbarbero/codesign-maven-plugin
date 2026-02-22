File signedFile = new File(basedir, 'target/classes/app.exe')
assert signedFile.exists(), "Signed file does not exist: ${signedFile}"
assert signedFile.text == 'signed-content', "Expected 'signed-content' but got: '${signedFile.text}'"
