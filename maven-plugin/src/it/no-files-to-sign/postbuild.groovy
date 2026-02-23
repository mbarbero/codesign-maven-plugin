File buildLog = new File(basedir, 'build.log')
assert buildLog.exists(), "Build log not found: ${buildLog}"
assert buildLog.text.contains('[WARNING] No files selected for signing'),
    "Expected 'No files selected for signing' warning in build.log"

// The exe file should be unchanged because the include pattern did not match it
File file = new File(basedir, 'target/classes/app.exe')
assert file.exists(), "File does not exist: ${file}"
assert file.text == 'unsigned-content', "Expected 'unsigned-content' but got: '${file.text}'"
