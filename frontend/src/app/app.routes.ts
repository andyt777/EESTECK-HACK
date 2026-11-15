import { Routes } from '@angular/router';
import { permissionGuard } from './core/auth';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./pages/control/control-center').then((m) => m.ControlCenter), title: 'Control Center — FileGrid' },
  { path: 'village', redirectTo: '' },
  { path: 'categories/:category', loadComponent: () => import('./pages/category/category-page').then((m) => m.CategoryPage), title: 'Categorie — FileGrid' },
  { path: 'files/:id', loadComponent: () => import('./pages/passport/passport-page').then((m) => m.PassportPage), title: 'File Passport — FileGrid' },
  { path: 'decisions', canActivate: [permissionGuard('policy:read')], loadComponent: () => import('./pages/decisions/decisions-page').then((m) => m.DecisionsPage), title: 'Decision Explorer — FileGrid' },
  { path: 'policies', canActivate: [permissionGuard('policy:read')], loadComponent: () => import('./pages/policies/policies-page').then((m) => m.PoliciesPage), title: 'Politici — FileGrid' },
  { path: 'admin/policies', redirectTo: 'policies' },
  { path: 'incidents', canActivate: [permissionGuard('incident:read')], loadComponent: () => import('./pages/incidents/incidents-page').then((m) => m.IncidentsPage), title: 'Incidente — FileGrid' },
  { path: 'incidents/:id', canActivate: [permissionGuard('incident:read')], loadComponent: () => import('./pages/incidents/incident-detail').then((m) => m.IncidentDetailPage), title: 'Incident — FileGrid' },
  { path: 'grid', canActivate: [permissionGuard('intelligence:read')], loadComponent: () => import('./pages/grid/grid-page').then((m) => m.GridPage), title: 'Grid Intelligence — FileGrid' },
  { path: 'grid-intelligence', redirectTo: 'grid' },
  { path: 'assistant', canActivate: [permissionGuard('assistant:use')], loadComponent: () => import('./pages/assistant/assistant-page').then((m) => m.AssistantPage), title: 'AI Assistant — FileGrid' },
  { path: 'field', canActivate: [permissionGuard('voice:use')], loadComponent: () => import('./pages/field/field-page').then((m) => m.FieldPage), title: 'Comunicare teren — FileGrid' },
  { path: 'performance', loadComponent: () => import('./pages/performance/performance-page').then((m) => m.PerformancePage), title: 'Performance — FileGrid' },
  { path: '**', redirectTo: '' },
];
