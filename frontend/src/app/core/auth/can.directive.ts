import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { Permission } from './auth.config';
import { AuthzService } from './authz.service';

/**
 * Structural directive: renders its template only when the user has the permission
 * (or any of the listed permissions). Optional else template:
 *   <button *fgCan="'policy:write'; else readOnly">…</button>
 * UI-only; the API re-checks every request.
 */
@Directive({ selector: '[fgCan]' })
export class CanDirective {
  private readonly authz = inject(AuthzService);
  private readonly tpl = inject(TemplateRef<unknown>);
  private readonly vcr = inject(ViewContainerRef);

  readonly fgCan = input.required<Permission | readonly Permission[]>();
  readonly fgCanElse = input<TemplateRef<unknown> | null>(null);

  private shown: 'then' | 'else' | null = null;

  constructor() {
    effect(() => {
      const allowed = this.authz.can(this.fgCan());
      const elseTpl = this.fgCanElse();
      const next = allowed ? 'then' : elseTpl ? 'else' : null;
      if (next === this.shown) return;
      this.vcr.clear();
      if (next === 'then') this.vcr.createEmbeddedView(this.tpl);
      else if (next === 'else' && elseTpl) this.vcr.createEmbeddedView(elseTpl);
      this.shown = next;
    });
  }
}
