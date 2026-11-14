import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./pages/control/control-center').then((m) => m.ControlCenter), title: 'Control Center — FileGrid' },
  { path: 'village', redirectTo: '' },
  { path: 'upload', loadComponent: () => import('./pages/upload/upload-page').then((m) => m.UploadPage), title: 'Încărcare — FileGrid' },
  { path: 'files', loadComponent: () => import('./pages/files/file-explorer').then((m) => m.FileExplorer), title: 'File Explorer — FileGrid' },
  { path: 'categories/:category', loadComponent: () => import('./pages/files/file-explorer').then((m) => m.FileExplorer), title: 'Categorie — FileGrid' },
  { path: 'files/:id', loadComponent: () => import('./pages/passport/passport-page').then((m) => m.PassportPage), title: 'File Passport — FileGrid' },
  { path: 'decisions', loadComponent: () => import('./pages/decisions/decisions-page').then((m) => m.DecisionsPage), title: 'Decision Explorer — FileGrid' },
  { path: 'policies', loadComponent: () => import('./pages/policies/policies-page').then((m) => m.PoliciesPage), title: 'Politici — FileGrid' },
  { path: 'admin/policies', redirectTo: 'policies' },
  { path: 'incidents', loadComponent: () => import('./pages/incidents/incidents-page').then((m) => m.IncidentsPage), title: 'Incidente — FileGrid' },
  { path: 'incidents/:id', loadComponent: () => import('./pages/incidents/incident-detail').then((m) => m.IncidentDetailPage), title: 'Incident — FileGrid' },
  { path: 'grid', loadComponent: () => import('./pages/grid/grid-page').then((m) => m.GridPage), title: 'Grid Intelligence — FileGrid' },
  { path: 'grid-intelligence', redirectTo: 'grid' },
  { path: 'assistant', loadComponent: () => import('./pages/assistant/assistant-page').then((m) => m.AssistantPage), title: 'AI Assistant — FileGrid' },
  { path: 'field', loadComponent: () => import('./pages/field/field-page').then((m) => m.FieldPage), title: 'Comunicare teren — FileGrid' },
  { path: 'performance', loadComponent: () => import('./pages/performance/performance-page').then((m) => m.PerformancePage), title: 'Performance — FileGrid' },
  { path: '**', redirectTo: '' },
];
