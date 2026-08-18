# OpenPanel — agent entry point

This file is only a pointer so that tools which auto-load `CLAUDE.md` start in the
right place. **The single source of truth is `README.md`.**

Before changing anything, read `README.md` → "Working in this repo as an agent"
(two repos in one checkout, runtime build branches, gates, definition of done,
do-not-touch list, admin-gating rule). Then verify with:

```sh
npm run typecheck && npm test && npm run build
```

Do not commit, push, deploy, or `adb install` unless the task explicitly says so.
