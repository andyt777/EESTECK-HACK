import { Component, DestroyRef, inject } from '@angular/core';
import { Api, Loader } from '../../core/api.service';
import { T } from '../../core/texts';
import { SHARED } from '../../shared/ui';

@Component({
  selector: 'fg-performance',
  imports: [...SHARED],
  template: `
  <div class="page">
    <div class="page-head"><div><div class="kicker">Observabilitate</div><h1>{{ t.performance.title }}</h1><div class="lead">{{ t.performance.lead }}</div></div>
      <a class="btn" href="/grafana/" target="_blank" rel="noopener"><span class="ms">monitoring</span>{{ t.performance.grafana }}</a></div>
    <div class="grid g4 mb">
      <div class="card kpi info"><span class="label">{{ t.control.kpiMeterRate }} <fg-origin [origin]="meter.data()?.dataOrigin" /></span><span class="value">{{ meter.data()?.readingsLastMinute | num }}</span><span class="sub">medie 5 min: {{ meter.data()?.readingsPerMinute | num: 1 }}</span></div>
      <div class="card kpi ok"><span class="label">{{ t.control.kpiReadingsTotal }}</span><span class="value">{{ meter.data()?.readingsStored | num }}</span><span class="sub">{{ meter.data()?.activeMeters | num }} contoare active</span></div>
      <div class="card kpi" [class.ok]="meter.data()?.duplicatesWritten === 0"><span class="label">{{ t.control.kpiDuplicates }}</span><span class="value">{{ meter.data()?.duplicatesWritten ?? '—' }}</span><span class="sub">{{ meter.data()?.duplicatesRejected | num }} {{ t.control.kpiRejected }}</span></div>
      <div class="card kpi" [class.warn]="(meter.data()?.gaps ?? 0) > 0"><span class="label">Goluri în citiri</span><span class="value">{{ meter.data()?.gaps | num }}</span><span class="sub">reconciliat {{ meter.data()?.reconciledAt | tm }}</span></div>
    </div>
    <div class="card">
      <div class="card-head"><h3>{{ t.control.storageHealth }}</h3></div>
      @if (health.data(); as hs) {
        <table class="tbl"><thead><tr><th>Storage</th><th>Stare</th><th>Latență</th><th>Ultima verificare</th></tr></thead><tbody>
        @for (h of hs; track h.storage) { <tr><td><fg-storage [storage]="h.storage" /></td><td><span class="row"><span class="dot" [class.ok]="h.status === 'UP'" [class.bad]="h.status === 'DOWN'"></span>{{ t.storage[h.status] }}</span></td><td class="mono">{{ h.latencyMs ?? '—' }} ms</td><td>{{ h.lastCheck | dt: true }}</td></tr> }
        </tbody></table>
      } @else { <fg-state [loading]="health.loading()" [unavailable]="health.unavailable()" [error]="health.error()" (retry)="health.load()" /> }
    </div>
  </div>`,
})
export class PerformancePage {
  readonly t = T;
  private readonly api = inject(Api);
  readonly meter = new Loader(() => this.api.meterStats());
  readonly health = new Loader(() => this.api.storageHealth());
  constructor() {
    this.meter.load(); this.health.load();
    const i = setInterval(() => { this.meter.load(true); this.health.load(true); }, 5000);
    inject(DestroyRef).onDestroy(() => clearInterval(i));
  }
}
