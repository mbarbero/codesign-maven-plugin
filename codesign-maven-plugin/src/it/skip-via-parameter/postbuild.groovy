File file = new File(basedir, 'target/classes/app.exe')
assert file.exists(), "File does not exist: ${file}"
assert file.text == 'unsigned-content', "Expected 'unsigned-content' but got: '${file.text}'"
