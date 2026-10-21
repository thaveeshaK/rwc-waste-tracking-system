# RWC frontend

React + TypeScript + Vite for the normal Crate/Pallet workflow. See the repository [README](../README.md) for setup, environment configuration, account provisioning, API details, and testing.

```powershell
npm.cmd ci
npm.cmd run dev
npm.cmd run build
npm.cmd run lint
```

The development and preview servers proxy `/api` to `http://localhost:8080`, or `API_PROXY_TARGET` when supplied. Use the same origin for frontend and API in production.
