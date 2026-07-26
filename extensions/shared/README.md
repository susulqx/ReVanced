# Shared ReVanced Extension

This directory is reserved for a shared Android extension module.

For the `app.unique.one` subscription bypass patch, no extension is required: all
code rewriting is performed directly by the bytecode patch using smali generation.

If future patches need runtime helper code, add it here and reference the built
`.rve` file with `extendWith("...")` in the patch.
