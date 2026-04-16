# Azure OAuth2 Configuration Verification Checklist

## CRITICAL: "Invalid credentials" error means Azure is rejecting the authentication
This error occurs **AT the Azure login endpoint**, not in your application. Your app code is correct.

---

## Step 1: Verify App Registration Exists in Azure Portal

**ACTION**: Go to [Azure Portal](https://portal.azure.com) → **App Registrations**

- [ ] Search for app with CLIENT_ID: `4a30973f-d855-4105-8b56-dfe21d85805e`
- [ ] Confirm app is found (if not found, app may be deleted or in wrong tenant)
- [ ] Check app status: **Should be ENABLED** (not disabled/deleted)

---

## Step 2: Verify Redirect URI is Registered (CRITICAL - Must Match Exactly)

**ACTION**: In the app → **Authentication** → **Redirect URIs** section

- [ ] Look for redirect URI section
- [ ] Verify this URI exists in the list:
  ```
  http://localhost:8080/login/oauth2/code/azure
  ```
- [ ] **EXACT MATCH REQUIRED**: No trailing slash, no uppercase, no modifications
- [ ] If missing: **Add it now** → Save

**If redirect URI doesn't exist in Azure, Azure will reject the login attempt.**

---

## Step 3: Verify Client Secret is Valid (Not Expired)

**ACTION**: In the app → **Certificates & secrets** → **Client Secrets** tab

- [ ] Look for client secret with value starting with: `9f3b9d68...`
- [ ] Check the **Expires** column:
  - [ ] If status shows **"Expired"** → Delete it and create a new one
  - [ ] If status shows a future date → Secret is valid
- [ ] If you see "Secret not found" or different values → Wrong credentials provided
  - [ ] Copy the current valid secret value
  - [ ] Update `application.yml` CLIENT_SECRET with the correct value

---

## Step 4: Verify Tenant ID is Correct

**ACTION**: In Azure Portal → **App Registrations** → Your app → **Overview**

- [ ] Look for "Directory (tenant) ID"
- [ ] Confirm it matches: `81a99674-94f7-4f8e-adde-b6affb1d04f7`
- [ ] If different → Update `application.yml` with correct tenant ID

---

## Step 5: Run Diagnostics Endpoint in Your App

**ACTION**: Once backend is started, run this command:

```bash
curl http://localhost:8080/api/auth/diagnostics
```

**Expected output will show**:
```json
{
  "clientId": "4a30...",
  "clientSecret": "***REDACTED***",
  "issuerUri": "https://login.microsoftonline.com/81a99674-94f7-4f8e-adde-b6affb1d04f7/v2.0",
  "redirectUri": "http://localhost:8080/login/oauth2/code/azure",
  "scopes": "openid,profile,email,offline_access",
  "authGrantType": "authorization_code"
}
```

**Verify**: All shown values match your Azure app registration exactly.

---

## Step 6: Check Frontend Configuration

**ACTION**: Verify frontend is pointing to correct backend

- [ ] Open [http://localhost:3000](http://localhost:3000)
- [ ] Check browser console (F12 → Console tab)
- [ ] No CORS errors should appear
- [ ] Click "Continue with Microsoft" button
- [ ] Button should redirect to `/api/auth/login` (shows in browser address bar)

---

## Step 7: Check Backend Logs for Azure Response

**ACTION**: Start backend with verbose logging and watch logs while attempting login

When trying to login, backend logs should show:
```
OAuth2 authentication failed: ...
```

This will show the actual error from Azure.

---

## Common "Invalid credentials" Causes

| Problem | Solution |
|---------|----------|
| Redirect URI not registered in Azure | Add `http://localhost:8080/login/oauth2/code/azure` to Authentication → Redirect URIs |
| Client Secret is expired | Generate new secret in Certificates & secrets |
| Client Secret is incorrect | Copy correct secret from Azure portal to `application.yml` |
| Wrong tenant ID | Verify tenant ID matches in both Azure and `application.yml` |
| App is disabled | Enable app in Azure portal Overview page |
| Wrong CLIENT_ID provided | Verify CLIENT_ID matches app registration in Azure |
| Credentials were test/placeholder values | Get real credentials from Azure portal |

---

## Next Steps

1. **Work through this checklist from top to bottom**
2. **After fixing any issues**, restart your backend:
   ```bash
   mvn spring-boot:run
   ```
3. **Try login again** at [http://localhost:3000](http://localhost:3000)
4. **If still failing**, provide output from `/api/auth/diagnostics` endpoint
5. **If still failing**, check backend logs for the actual Azure error message

---

## Backend Logs Location

When you start the app:
```bash
mvn spring-boot:run
```

Watch for lines like:
```
OAuth2 authentication failed: ...
Root cause: ...
Azure OAuth login successful: name=..., email=...
```

Share these log messages if you need further help.

---

## Configuration Files to Update if Needed

**File**: [logcontroller/src/main/resources/application.yml](../logcontroller/src/main/resources/application.yml)

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          azure:
            client-id: 4a30973f-d855-4105-8b56-dfe21d85805e  # ← Verify matches Azure
            client-secret: 9f3b9d68-e831-44ad-bff5-e0c2fb0a8a5b  # ← Verify matches Azure
            scopes: openid,profile,email,offline_access
            authorization-grant-type: authorization_code
            redirect-uri: http://localhost:8080/login/oauth2/code/azure  # ← Must match Azure
        provider:
          azure:
            issuer-uri: https://login.microsoftonline.com/81a99674-94f7-4f8e-adde-b6affb1d04f7/v2.0
```

If ANY of these values are wrong, Azure will reject the login.

---

## Testing Checklist (After Verification)

1. [ ] Start backend: `mvn spring-boot:run`
2. [ ] Start frontend: `npm run dev`
3. [ ] Navigate to [http://localhost:3000](http://localhost:3000)
4. [ ] Click "Continue with Microsoft" button
5. [ ] Get redirected to Azure login page (`login.microsoftonline.com`)
6. [ ] Enter Azure credentials
7. [ ] **EXPECTED**: Redirected back to dashboard, logged in
8. [ ] **IF ERROR**: Go back to top of this checklist
