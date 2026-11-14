import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Subject, debounceTime, filter, switchMap, of, catchError } from 'rxjs';
import { NAV_PERMISSIONS } from './app.routes';
import { Api } from './core/api.service';
import { AuthzService } from './core/auth/authz.service';
import { CanDirective } from './core/auth/can.directive';
import { EventStream } from './core/events.service';
import { FileSummary } from './core/models';
import { T } from './core/texts';
import { UiState } from './core/ui-state.service';
import { AssistantChat } from './features/assistant-chat';
import { SimPhone } from './features/sim-phone';
import { SHARED } from './shared/ui';

interface NavItem { path: string; label: string; icon: string; exact?: boolean; }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSnackBarModule, AssistantChat, SimPhone, CanDirective, ...SHARED],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  readonly t = T;
  readonly ui = inject(UiState);
  readonly events = inject(EventStream);
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly snack = inject(MatSnackBar);
  private readonly oidc = inject(OidcSecurityService);
  readonly authz = inject(AuthzService);
  readonly authenticated = computed(() => this.oidc.authenticated().isAuthenticated);

  readonly sections: { title: string; items: NavItem[] }[] = [
    { title: T.nav.sectionOps, items: [
      { path: '/', label: T.nav.control, icon: 'hub', exact: true },
      { path: '/upload', label: T.nav.upload, icon: 'cloud_upload' },
      { path: '/incidents', label: T.nav.incidents, icon: 'shield' },
      { path: '/field', label: T.nav.field, icon: 'support_agent' },
    ] },
    { title: T.nav.sectionData, items: [
      { path: '/files', label: T.nav.files, icon: 'folder_open' },
      { path: '/decisions', label: T.nav.decisions, icon: 'account_tree' },
      { path: '/policies', label: T.nav.policies, icon: 'rule' },
    ] },
    { title: T.nav.sectionIntel, items: [
      { path: '/grid', label: T.nav.grid, icon: 'electric_bolt' },
      { path: '/assistant', label: T.nav.assistant, icon: 'smart_toy' },
      { path: '/performance', label: T.nav.performance, icon: 'speed' },
    ] },
  ];

  /** Menu sections filtered by the user's permissions (the server still checks every call). */
  readonly visibleSections = computed(() => {
    this.authz.permissions();
    return this.sections
      .map((s) => ({ ...s, items: s.items.filter((i) => this.authz.can(NAV_PERMISSIONS[i.path] ?? 'file:read')) }))
      .filter((s) => s.items.length > 0);
  });

  readonly url = signal('/');
  readonly onVillage = computed(() => this.url() === '/' || this.url().startsWith('/?'));
  readonly searchOpen = signal(false);
  readonly searchQ = signal('');
  readonly searchResults = signal<FileSummary[]>([]);
  readonly searchSel = signal(0);
  private readonly search$ = new Subject<string>();

  constructor() {
    // Live streams and the profile need a token: start them once the OIDC session exists.
    effect(() => {
      if (this.authenticated()) {
        this.authz.load().subscribe();
        this.events.start();
      } else {
        this.events.stop();
      }
    });
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe((e) => {
      this.url.set((e as NavigationEnd).urlAfterRedirects);
      this.ui.simulatedOnPage.set(false);
    });
    this.search$.pipe(
      debounceTime(180),
      switchMap((q) => (q.trim().length < 2 ? of({ items: [] as FileSummary[] }) :
        this.api.files({ q, size: 8, excludeMeters: false }).pipe(catchError(() => of({ items: [] as FileSummary[] }))))),
    ).subscribe((r) => { this.searchResults.set(r.items); this.searchSel.set(0); });

    this.events.incidentEvents$.subscribe((e) => {
      if (e.incident?.severity === 'CRITICAL' && e.kind === 'INCIDENT_CREATED') {
        const ref = this.snack.open(`${T.incidents.critical}: ${e.incident.code} — ${e.incident.title ?? e.incident.typeLabel}`, T.app.open, { duration: 9000 });
        ref.onAction().subscribe(() => this.router.navigate(['/incidents', e.incident!.id]));
      }
    });
    effect(() => document.documentElement.classList.toggle('reduce-motion', !this.ui.animations()));
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      this.openSearch();
    } else if (e.key === 'Escape' && this.searchOpen()) {
      this.searchOpen.set(false);
    }
  }

  logout(): void {
    this.events.stop();
    this.authz.clear();
    this.oidc.logoff().subscribe({ error: () => this.oidc.logoffLocal() });
  }

  openSearch(): void {
    this.searchOpen.set(true);
    setTimeout(() => (document.getElementById('fg-search') as HTMLInputElement | null)?.focus(), 30);
  }

  onSearch(q: string): void { this.searchQ.set(q); this.search$.next(q); }

  searchKey(e: KeyboardEvent): void {
    const n = this.searchResults().length;
    if (e.key === 'ArrowDown') { this.searchSel.set(Math.min(n - 1, this.searchSel() + 1)); e.preventDefault(); }
    if (e.key === 'ArrowUp') { this.searchSel.set(Math.max(0, this.searchSel() - 1)); e.preventDefault(); }
    if (e.key === 'Enter') {
      const f = this.searchResults()[this.searchSel()];
      const q = this.searchQ().trim();
      if (f) this.go(f.id);
      else if (/^[0-9a-f-]{36}$/i.test(q)) this.go(q);
    }
  }

  go(id: string): void {
    this.searchOpen.set(false);
    this.searchQ.set('');
    this.searchResults.set([]);
    this.router.navigate(['/files', id]);
  }
}
