import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, ElementRef, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { DomSanitizer } from '@angular/platform-browser';
import { SecurityContext } from '@angular/core';

type Status = 'AGUARDANDO_DESENVOLVIMENTO' | 'EM_DESENVOLVIMENTO' | 'DESENVOLVIMENTO_EM_PROGRESSO' | 'EM_TESTE' | 'REABERTA';
type Person = { id: number; login: string; nome: string };
type Demand = { id: number; titulo: string; descricao?: string; status: Status; responsavelId: number | ''; responsavel: string; criadoEm: string; atualizadoEm: string };
type Attachment = { id: number; nome: string; tipo: string; tamanho: number };
type SavedFilter = { id: number; nome: string; texto: string | null; status: Status | null; responsavelId: number | null };
const statuses: {value: Status; label: string}[] = [
  {value: 'AGUARDANDO_DESENVOLVIMENTO', label: 'Aguardando desenvolvimento'},
  {value: 'EM_DESENVOLVIMENTO', label: 'Em desenvolvimento'},
  {value: 'DESENVOLVIMENTO_EM_PROGRESSO', label: 'Desenvolvimento em progresso'},
  {value: 'EM_TESTE', label: 'Em teste'},
  {value: 'REABERTA', label: 'Reaberta'}
];

@Component({selector: 'app-root', standalone: true, imports: [CommonModule, FormsModule], templateUrl: './app.html', styleUrl: './app.scss'})
export class App {
  private http = inject(HttpClient);
  private sanitizer = inject(DomSanitizer);
  @ViewChild('editor') editor?: ElementRef<HTMLDivElement>;
  statuses = statuses;
  me = signal<Person | null>(null);
  page = signal<'demandas' | 'painel'>('demandas');
  demands = signal<Demand[]>([]);
  people = signal<Person[]>([]);
  filters = signal<SavedFilter[]>([]);
  attachments = signal<Attachment[]>([]);
  selected = signal<number[]>([]);
  current = signal<Demand | null>(null);
  busy = signal(false);
  error = signal('');
  notice = signal('');
  login = 'arthur';
  password = '';
  search = '';
  filterStatus = '';
  filterPerson = '';
  filterName = '';
  bulkStatus = '';
  bulkPerson = '';
  bulkAssign = false;
  title = '';
  description = '';
  status: Status = 'AGUARDANDO_DESENVOLVIMENTO';
  responsible = '';

  constructor() { void this.initialize(); }

  async initialize() {
    try { await firstValueFrom(this.http.get('/api/csrf')); this.me.set(await firstValueFrom(this.http.get<Person>('/api/me'))); await this.refresh(); }
    catch { this.me.set(null); }
  }
  async signIn() {
    this.error.set(''); this.busy.set(true);
    try {
      const body = new HttpParams().set('username', this.login).set('password', this.password);
      await firstValueFrom(this.http.post('/api/login', body, {responseType: 'text'}));
      await firstValueFrom(this.http.get('/api/csrf'));
      this.password = ''; this.me.set(await firstValueFrom(this.http.get<Person>('/api/me')));
      await this.refresh();
    } catch { this.error.set('Usuário ou senha inválidos.'); }
    finally { this.busy.set(false); }
  }
  async signOut() {
    await firstValueFrom(this.http.post('/api/logout', {}));
    this.me.set(null); this.current.set(null); this.demands.set([]);
    await firstValueFrom(this.http.get('/api/csrf'));
  }
  async refresh() {
    const [people, filters] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>('/api/usuarios')),
      firstValueFrom(this.http.get<SavedFilter[]>('/api/filtros'))
    ]);
    this.people.set(people); this.filters.set(filters); await this.searchDemands();
  }
  async searchDemands() {
    let params = new HttpParams();
    if (this.search.trim()) params = params.set('texto', this.search.trim());
    if (this.filterStatus) params = params.set('status', this.filterStatus);
    if (this.filterPerson) params = params.set('responsavelId', this.filterPerson);
    try { this.demands.set(await firstValueFrom(this.http.get<Demand[]>('/api/demandas', {params}))); }
    catch { this.error.set('Não foi possível carregar as demandas.'); }
  }
  clearFilters() { this.search = ''; this.filterStatus = ''; this.filterPerson = ''; void this.searchDemands(); }
  statusLabel(value: string) { return statuses.find(item => item.value === value)?.label ?? value; }
  openNew() {
    this.current.set(null); this.title = ''; this.description = ''; this.status = 'AGUARDANDO_DESENVOLVIMENTO'; this.responsible = '';
    this.attachments.set([]); this.error.set('');
  }
  async open(demand: Demand) {
    this.error.set('');
    try {
      const full = await firstValueFrom(this.http.get<Demand>(`/api/demandas/${demand.id}`));
      this.current.set(full); this.title = full.titulo; this.description = full.descricao ?? '';
      this.status = full.status; this.responsible = full.responsavelId ? String(full.responsavelId) : '';
      this.attachments.set(await firstValueFrom(this.http.get<Attachment[]>(`/api/demandas/${demand.id}/anexos`)));
      this.page.set('demandas');
      setTimeout(() => { if (this.editor) this.editor.nativeElement.innerHTML = this.sanitizer.sanitize(SecurityContext.HTML, this.description) ?? ''; });
    } catch { this.error.set('Não foi possível abrir a demanda.'); }
  }
  editing = signal(false);
  editNew() { this.openNew(); this.editing.set(true); setTimeout(() => { if (this.editor) this.editor.nativeElement.innerHTML = ''; }); }
  async editExisting(demand: Demand) { await this.open(demand); this.editing.set(true); }
  closeEditor() { this.editing.set(false); this.current.set(null); }
  async save() {
    if (!this.title.trim()) { this.error.set('Informe o título da demanda.'); return; }
    this.description = this.sanitizer.sanitize(SecurityContext.HTML, this.editor?.nativeElement.innerHTML ?? '') ?? '';
    this.busy.set(true); this.error.set('');
    const body = {titulo: this.title, descricao: this.description, status: this.status, responsavelId: this.responsible ? Number(this.responsible) : null};
    try {
      const request = this.current() ? this.http.put<Demand>(`/api/demandas/${this.current()!.id}`, body) : this.http.post<Demand>('/api/demandas', body);
      const saved = await firstValueFrom(request);
      this.current.set(saved); this.notice.set('Demanda salva.'); await this.searchDemands();
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { this.busy.set(false); }
  }
  async upload(event: Event) {
    const input = event.target as HTMLInputElement; const file = input.files?.[0]; const current = this.current();
    if (!file || !current) { this.error.set('Salve a demanda antes de adicionar uma foto.'); return; }
    const body = new FormData(); body.append('arquivo', file);
    try {
      const result = await firstValueFrom(this.http.post<{id:number;url:string}>(`/api/demandas/${current.id}/anexos`, body));
      this.attachments.set(await firstValueFrom(this.http.get<Attachment[]>(`/api/demandas/${current.id}/anexos`)));
      const image = document.createElement('img'); image.src = result.url; image.alt = file.name;
      this.editor?.nativeElement.append(image);
      await this.save();
      this.notice.set('Foto inserida na demanda.');
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { input.value = ''; }
  }
  async removeAttachment(attachment: Attachment) {
    const current = this.current(); if (!current) return;
    await firstValueFrom(this.http.delete(`/api/demandas/${current.id}/anexos/${attachment.id}`));
    this.attachments.update(items => items.filter(item => item.id !== attachment.id));
    this.editor?.nativeElement.querySelectorAll('img').forEach(image => { if (image.src.endsWith(`/anexos/${attachment.id}/arquivo`)) image.remove(); });
    await this.save();
  }
  toggle(id: number, checked: boolean) { this.selected.update(ids => checked ? [...ids, id] : ids.filter(value => value !== id)); }
  toggleAll(checked: boolean) { this.selected.set(checked ? this.demands().map(item => item.id) : []); }
  async applyBulk() {
    if (!this.selected().length || (!this.bulkStatus && !this.bulkAssign)) return;
    try {
      await firstValueFrom(this.http.post('/api/demandas/lote', {ids: this.selected(), status: this.bulkStatus || null,
        alterarResponsavel: this.bulkAssign, responsavelId: this.bulkPerson ? Number(this.bulkPerson) : null}));
      this.notice.set(`${this.selected().length} demanda(s) atualizada(s).`); this.selected.set([]); await this.searchDemands();
    } catch (e) { this.error.set(this.errorText(e)); }
  }
  async saveFilter() {
    if (!this.filterName.trim()) { this.error.set('Dê um nome ao filtro.'); return; }
    try {
      await firstValueFrom(this.http.post('/api/filtros', {nome: this.filterName.trim(), texto: this.search || null,
        status: this.filterStatus || null, responsavelId: this.filterPerson ? Number(this.filterPerson) : null}));
      this.filters.set(await firstValueFrom(this.http.get<SavedFilter[]>('/api/filtros')));
      this.filterName = ''; this.notice.set('Filtro salvo no painel.');
    } catch (e) { this.error.set(this.errorText(e)); }
  }
  applyFilter(filter: SavedFilter) {
    this.search = filter.texto ?? ''; this.filterStatus = filter.status ?? '';
    this.filterPerson = filter.responsavelId ? String(filter.responsavelId) : '';
    this.page.set('demandas'); void this.searchDemands();
  }
  async deleteFilter(filter: SavedFilter) {
    await firstValueFrom(this.http.delete(`/api/filtros/${filter.id}`));
    this.filters.update(items => items.filter(item => item.id !== filter.id));
  }
  count(status: Status) { return this.demands().filter(item => item.status === status).length; }
  private errorText(e: unknown) { return e instanceof HttpErrorResponse && e.status === 413 ? 'A foto excede o limite de 10 MB.' : 'Não foi possível concluir a operação.'; }
}
