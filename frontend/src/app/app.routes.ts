import { Routes } from '@angular/router';
import { autoLoginPartialRoutesGuard } from 'angular-auth-oidc-client';
import { permissionGuard } from './core/auth/permission.guard';

/**
 * Every route sits under one parent guarded by the OIDC auto-login guard (redirects to Keycloak when there is
 * no valid session); each page then checks its permission (docs/AUTH-DESIGN.md §3, §5). The OIDC callback
 * (`/?code=…&state=…`) is processed by `withAppInitializerAuthCheck()` before the first navigation.
 * Note: route `data` would be forwarded to Keycloak as custom params by the auto-login guard, so none is used.
 */
export const routes: Routes = [
  {
    path: '',
    canActivateChild: [autoLoginPartialRoutesGuard],
    children: [
      { path: '', pathMatch: 'full', canActivate: [permissionGuard('file:read')], loadComponent: () => import('./pages/control/control-center').then((m) => m.ControlCenter), title: 'Control Center — FileGrid' },
      { path: 'village', redirectTo: '' },
      { path: 'upload', canActivate: [permissionGuard('file:write')], loadComponent: () => import('./pages/upload/upload-page').then((m) => m.UploadPage), title: 'Încărcare — FileGrid' },
      { path: 'files', canActivate: [permissionGuard('file:read')], loadComponent: () => import('./pages/files/file-explorer').then((m) => m.FileExplorer), title: 'File Explorer — FileGrid' },
      { path: 'categories/:category', canActivate: [permissionGuard('file:read')], loadComponent: () => import('./pages/files/file-explorer').then((m) => m.FileExplorer), title: 'Categorie — FileGrid' },
      { path: 'files/:id', canActivate: [permissionGuard('file:read')], loadComponent: () => import('./pages/passport/passport-page').then((m) => m.PassportPage), title: 'File Passport — FileGrid' },
      { path: 'decisions', canActivate: [permissionGuard('file:read')], loadComponent: () => import('./pages/decisions/decisions-page').then((m) => m.DecisionsPage), title: 'Decision Explorer — FileGrid' },
      // Viewing needs policy:read; editing inside the page is gated by policy:write (*fgCan).
      { path: 'policies', canActivate: [permissionGuard('policy:read')], loadComponent: () => import('./pages/policies/policies-page').then((m) => m.PoliciesPage), title: 'Politici — FileGrid' },
      { path: 'admin/policies', redirectTo: 'policies' },
      { path: 'incidents', canActivate: [permissionGuard('incident:read')], loadComponent: () => import('./pages/incidents/incidents-page').then((m) => m.IncidentsPage), title: 'Incidente — FileGrid' },
      { path: 'incidents/:id', canActivate: [permissionGuard('incident:read')], loadComponent: () => import('./pages/incidents/incident-detail').then((m) => m.IncidentDetailPage), title: 'Incident — FileGrid' },
      { path: 'grid', canActivate: [permissionGuard('intelligence:read')], loadComponent: () => import('./pages/grid/grid-page').then((m) => m.GridPage), title: 'Grid Intelligence — FileGrid' },
      { path: 'grid-intelligence', redirectTo: 'grid' },
      { path: 'assistant', canActivate: [permissionGuard('assistant:use')], loadComponent: () => import('./pages/assistant/assistant-page').then((m) => m.AssistantPage), title: 'AI Assistant — FileGrid' },
      { path: 'field', canActivate: [permissionGuard('voice:use')], loadComponent: () => import('./pages/field/field-page').then((m) => m.FieldPage), title: 'Comunicare teren — FileGrid' },
      { path: 'performance', canActivate: [permissionGuard('storage:read')], loadComponent: () => import('./pages/performance/performance-page').then((m) => m.PerformancePage), title: 'Performance — FileGrid' },
      { path: 'forbidden', loadComponent: () => import('./pages/forbidden/forbidden-page').then((m) => m.ForbiddenPage), title: 'Acces interzis — FileGrid' },
      { path: '**', redirectTo: '' },
    ],
  },
];

/** Permission required by each top-level navigation path (used to hide menu entries). */
export const NAV_PERMISSIONS: Record<string, string> = {
  '/': 'file:read',
  '/upload': 'file:write',
  '/incidents': 'incident:read',
  '/field': 'voice:use',
  '/files': 'file:read',
  '/decisions': 'file:read',
  '/policies': 'policy:read',
  '/grid': 'intelligence:read',
  '/assistant': 'assistant:use',
  '/performance': 'storage:read',
};
