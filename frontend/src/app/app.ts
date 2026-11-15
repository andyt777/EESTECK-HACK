import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Subject, debounceTime, filter, switchMap, of, catchError } from 'rxjs';
import { Api } from './core/api.service';
import { AuthzService } from './core/auth';
import { EventStream } from './core/events.service';
import { FileSummary } from './core/models';
import { T } from './core/texts';
import { UiState } from './core/ui-state.service';
import { AssistantChat } from './features/assistant-chat';
import { SimPhone } from './features/sim-phone';
import { SHARED } from './shared/ui';

interface NavItem { path: string; label: string; icon: string; exact?: boolean; perm?: string; }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatSnackBarModule, AssistantChat, SimPhone, ...SHARED],
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

  readonly authz = inject(AuthzService);

  private readonly allSections: { title: string; items: NavItem[] }[] = [
    { title: T.nav.sectionOps, items: [
      { path: '/', label: T.nav.control, icon: 'hub', exact: true },
      { path: '/incidents', label: T.nav.incidents, icon: 'shield', perm: 'incident:read' },
      { path: '/field', label: T.nav.field, icon: 'support_agent', perm: 'voice:use' },
    ] },
    { title: T.nav.sectionIntel, items: [
      { path: '/grid', label: T.nav.grid, icon: 'electric_bolt', perm: 'intelligence:read' },
      { path: '/assistant', label: T.nav.assistant, icon: 'smart_toy', perm: 'assistant:use' },
      { path: '/performance', label: T.nav.performance, icon: 'speed' },
    ] },
    { title: T.nav.sectionAdmin, items: [
      { path: '/decisions', label: T.nav.decisions, icon: 'account_tree', perm: 'policy:read' },
      { path: '/policies', label: T.nav.policies, icon: 'rule', perm: 'policy:read' },
    ] },
  ];
  /** Only the pages the signed-in role may open. */
  readonly sections = computed(() => {
    const perms = this.authz.permissions();
    return this.allSections
      .map((s) => ({ ...s, items: s.items.filter((i) => !i.perm || perms.has(i.perm)) }))
      .filter((s) => s.items.length);
  });

  readonly url = signal('/');
  readonly onVillage = computed(() => this.url() === '/' || this.url().startsWith('/?'));
  /** The sidebar is hidden by default and slides in from the menu button. */
  readonly menuOpen = signal(false);
  readonly searchOpen = signal(false);
  readonly searchQ = signal('');
  readonly searchResults = signal<FileSummary[]>([]);
  readonly searchSel = signal(0);
  private readonly search$ = new Subject<string>();

  constructor() {
    this.events.start();
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe((e) => {
      this.url.set((e as NavigationEnd).urlAfterRedirects);
      this.ui.simulatedOnPage.set(false);
      this.menuOpen.set(false);
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
    // Keyboard: opening the menu moves focus into it (the page behind is inert); closing returns it to ☰.
    let wasOpen = false;
    effect(() => {
      const open = this.menuOpen();
      if (open === wasOpen) return;
      wasOpen = open;
      setTimeout(() => {
        const target = open
          ? document.querySelector<HTMLElement>('aside.nav .nav-item.on, aside.nav .nav-item')
          : document.getElementById('fg-menu-button');
        const active = document.activeElement;
        if (open || active === document.body || active?.closest('aside.nav')) target?.focus();
      });
    });
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      this.openSearch();
    } else if (e.key === 'Escape' && this.searchOpen()) {
      this.searchOpen.set(false);
    } else if (e.key === 'Escape' && this.menuOpen()) {
      this.menuOpen.set(false);
    }
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
