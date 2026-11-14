import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthzService } from '../../core/auth/authz.service';
import { T } from '../../core/texts';

/** Shown when a route guard refuses navigation (missing permission or profile not loadable). */
@Component({
  selector: 'fg-forbidden',
  imports: [RouterLink],
  template: `
  <div class="page">
    <div class="state bad" role="alert">
      <span class="ms big">block</span>
      <b>{{ t.auth.forbiddenTitle }}</b>
      <span class="muted">{{ authz.error() || t.auth.forbiddenPage }}</span>
      @if (authz.roleLabels().length) {
        <span class="muted small">{{ t.auth.roleLabel }}: {{ authz.roleLabels().join(', ') }}</span>
      }
      <a class="btn ghost sm" routerLink="/">{{ t.auth.backHome }}</a>
    </div>
  </div>`,
})
export class ForbiddenPage {
  readonly t = T;
  readonly authz = inject(AuthzService);
}
