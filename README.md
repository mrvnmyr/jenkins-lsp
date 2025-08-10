# Jenkins LSP

`$ go-task build`

## YCM Integration
e.g.:
```
  \   {
  \     'name': 'groovy',
  \     'cmdline': [ 'java', '-jar', $HOME.'/workspace/jenkins-lsp/target/jenkins-lsp-1.0.0-all.jar' ],
  \     'filetypes': [ 'groovy', 'gvy', 'gy', 'gsh' ],
  \   }
```

# TODO
- [X] Add basic Parsing/Error Reporting
- [X] Add basic GoTo
- [X] Add basic autocompletion
- [ ] Add wrongReturnType and returnUnknownIdentifier Errors/Warnings
- [ ] Find References
- [ ] Jump to Stdlib internals
- [ ] Jump to Jenkins internals
