import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthzService } from './authz.service';
import { CanDirective } from './can.directive';

@Component({
  imports: [CanDirective],
  template: `
    <button *fgCan="'policy:write'" id="edit">edit</button>
    <span *fgCan="['pii:read', 'file:read-suspect']" id="any">any</span>
    <b *fgCan="'pii:read'; else masked" id="name">Ion Popescu</b>
    <ng-template #masked><i id="masked">***</i></ng-template>
  `,
})
class Host {}

describe('CanDirective (*fgCan)', () => {
  let authz: AuthzService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    authz = TestBed.inject(AuthzService);
  });

  async function render() {
    const f = TestBed.createComponent(Host);
    await f.whenStable();
    return f;
  }

  const q = (el: HTMLElement, id: string) => el.querySelector('#' + id);

  it('renders nothing (or the else template) without permissions', async () => {
    authz.set({ actor: 'field.demo', roles: ['field'], permissions: ['file:read'] });
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    expect(q(el, 'edit')).toBeNull();
    expect(q(el, 'any')).toBeNull();
    expect(q(el, 'name')).toBeNull();
    expect(q(el, 'masked')?.textContent).toBe('***');
  });

  it('renders the content when the permission is present (any-of for lists)', async () => {
    authz.set({ actor: 'connections.demo', roles: ['connections'], permissions: ['file:read', 'pii:read'] });
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    expect(q(el, 'edit')).toBeNull();
    expect(q(el, 'any')).not.toBeNull();
    expect(q(el, 'name')?.textContent).toBe('Ion Popescu');
    expect(q(el, 'masked')).toBeNull();
  });

  it('reacts when permissions change (signal based)', async () => {
    authz.set({ actor: 'x', roles: [], permissions: [] });
    const f = await render();
    const el = f.nativeElement as HTMLElement;
    expect(q(el, 'edit')).toBeNull();

    authz.set({ actor: 'admin.demo', roles: ['admin'], permissions: ['policy:write', 'pii:read'] });
    await f.whenStable();
    expect(q(el, 'edit')).not.toBeNull();
    expect(q(el, 'name')).not.toBeNull();
    expect(q(el, 'masked')).toBeNull();

    authz.clear();
    await f.whenStable();
    expect(q(el, 'edit')).toBeNull();
    expect(q(el, 'masked')).not.toBeNull();
  });
});
