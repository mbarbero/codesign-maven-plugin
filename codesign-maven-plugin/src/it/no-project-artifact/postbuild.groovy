File buildLog = new File(basedir, 'build.log')
assert buildLog.exists(), "Build log not found: ${buildLog}"
assert buildLog.text.contains('[WARNING] No files selected for signing'),
    "Expected 'No files selected for signing' warning in build.log"
