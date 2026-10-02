import * as vscode from 'vscode';

import type { SyncService, SyncState } from '../sync/syncService.ts';

export class StatusBar implements vscode.Disposable {
  private readonly item = vscode.window.createStatusBarItem('androidBridge.status', vscode.StatusBarAlignment.Left, 10);

  constructor(service: SyncService) {
    this.item.name = 'Android Bridge';
    this.item.command = 'androidBridge.statusMenu';
    service.onDidChangeState((state) => this.render(state));
    this.render(service.state, service);
    this.item.show();
  }

  private render(state: SyncState, service?: SyncService): void {
    switch (state.kind) {
      case 'idle': {
        const variant = service?.lastModel() ? 'not synced this session' : 'not synced';
        this.item.text = '$(sync) Android';
        this.item.tooltip = `Android Bridge: ${variant}. Click to sync.`;
        this.item.backgroundColor = undefined;
        break;
      }
      case 'syncing':
        this.item.text = '$(sync~spin) Android: syncing…';
        this.item.tooltip = state.message;
        this.item.backgroundColor = undefined;
        break;
      case 'synced':
        this.item.text = `${state.problems > 0 ? '$(warning)' : '$(check)'} Android: ${state.variant ?? 'synced'}`;
        this.item.tooltip =
          `Synced at ${new Date(state.at).toLocaleTimeString()}` +
          (state.problems > 0 ? ` with ${state.problems} problem(s), see the log` : '') +
          '. Click for actions.';
        this.item.backgroundColor = undefined;
        break;
      case 'failed':
        this.item.text = '$(error) Android: sync failed';
        this.item.tooltip = state.message;
        this.item.backgroundColor = new vscode.ThemeColor('statusBarItem.errorBackground');
        break;
    }
  }

  dispose(): void {
    this.item.dispose();
  }
}
