#!/usr/bin/env bash
set -eo pipefail

# set -x
out=/tmp/query

task build



{
echo '```'
    flatten -o
echo '```'
echo ""
echo "This is a flattened filetree represented as a YAML."
echo ""
echo 'integration-tests: '
echo '```'
{ ./integration-tests.groovy  2>&1 || true; }
echo '```'
echo ""
echo "Implement what is required to fix the failing integration tests and output it in the same flattened filetree YAML structure as was provided before."
echo 'Add additional debug prints in case they are necessary to confirm the fixes.'
echo ""
echo "If files are not changed don't output them."

} > "$out"

# exit 0

# cat "${HOME}/workspace/jenkins-lsp/src/main/groovy/example/HelloWorld.groovy" >> "$out"

# echo '' >> "$out"

# { ./integration-test-runner.groovy -d 2>&1 | tee -a "$out" || true; }
# # Find the newest file matching the pattern
# newest_file=$(ls -t /tmp/groovycompleter_*.log 2>/dev/null | head -n 1)
# cat "$newest_file" >> "$out"
# echo '```' >> "$out"

# echo '' >> "$out"
# echo 'Fix the failing integration test cases above.' >> "$out"
# echo '' >> "$out"

# echo 'Skip any explanations and straight paste the fully changed complete code without any abbreviations.' >> "$out"
# echo 'Add more debug logging if necessary.' >> "$out"

vim + "$out"

if [[ ! -s "$out" ]] || ! grep -q '[^[:space:]]' "$out"; then
    echo "Empty file, aborting..."
    exit 1
fi

cb -i < "$out"
echo "Copied '$out' to clipboard!"
echo "Now paste it to ChatGPT and wait for the result."

wait-for-enter "after you have copied the result to your clipboard"

./exchange.sh

./integration-test-runner.groovy
