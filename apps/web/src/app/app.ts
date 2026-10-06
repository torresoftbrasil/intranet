import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, ElementRef, HostListener, ViewChild, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { DomSanitizer } from '@angular/platform-browser';
import { SecurityContext } from '@angular/core';

type Status = 'AGUARDANDO_DESENVOLVIMENTO' | 'EM_DESENVOLVIMENTO' | 'DESENVOLVIMENTO_EM_PROGRESSO' | 'EM_TESTE' | 'REABERTA';
type Person = { id: number; login: string; nome: string };
type Demand = { id: number; titulo: string; descricao?: string; status: Status; responsavelId: number | ''; responsavel: string; criadoEm: string; atualizadoEm: string };
type Attachment = { id: number; nome: string; tipo: string; tamanho: number };
type PendingImage = { id: number; file: File; previewUrl: string };
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
  @ViewChild('paletteInput') paletteInput?: ElementRef<HTMLInputElement>;
  statuses = statuses;
  me = signal<Person | null>(null);
  page = signal<'demandas' | 'painel'>('demandas');
  demands = signal<Demand[]>([]);
  people = signal<Person[]>([]);
  filters = signal<SavedFilter[]>([]);
  attachments = signal<Attachment[]>([]);
  pendingImages = signal<PendingImage[]>([]);
  private nextImageId = 0;
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
    } catch (e) {
      this.error.set(e instanceof HttpErrorResponse && e.status === 401
        ? 'Usuário ou senha inválidos.'
        : 'Não foi possível acessar o servidor. Tente novamente em instantes.');
    }
    finally { this.busy.set(false); }
  }
  async signOut() {
    this.clearPendingImages();
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
    this.selected.set([]);
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
      this.clearPendingImages();
      const full = await firstValueFrom(this.http.get<Demand>(`/api/demandas/${demand.id}`));
      this.current.set(full); this.title = full.titulo; this.description = full.descricao ?? '';
      this.status = full.status; this.responsible = full.responsavelId ? String(full.responsavelId) : '';
      this.attachments.set(await firstValueFrom(this.http.get<Attachment[]>(`/api/demandas/${demand.id}/anexos`)));
      this.page.set('demandas');
      setTimeout(() => { if (this.editor) this.editor.nativeElement.innerHTML = this.sanitizer.sanitize(SecurityContext.HTML, this.description) ?? ''; });
    } catch { this.error.set('Não foi possível abrir a demanda.'); }
  }
  editing = signal(false);
  menuOpen = signal(false);
  paletteOpen = signal(false);
  filtersOpen = signal(false);
  commandText = '';
  paletteQuery = '';

  @HostListener('document:keydown', ['$event'])
  handleShortcut(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault(); this.menuOpen.set(false); this.paletteOpen.update(open => !open);
      if (this.paletteOpen()) setTimeout(() => this.paletteInput?.nativeElement.focus());
    } else if (event.key === 'Escape') { this.paletteOpen.set(false); this.menuOpen.set(false); }
  }

  navigate(page: 'demandas' | 'painel') {
    this.page.set(page); this.closeEditor(); this.menuOpen.set(false); this.paletteOpen.set(false);
  }

  useCommand(value = this.commandText) {
    const text = value.trim(); if (!text && !this.pendingImages().length) return;
    this.paletteOpen.set(false);
    if (!this.pendingImages().length && /^(buscar|pesquisar)\s+/i.test(text)) {
      this.search = text.replace(/^(buscar|pesquisar)\s+/i, '');
      this.filtersOpen.set(true); this.navigate('demandas'); void this.searchDemands();
    } else if (!this.pendingImages().length && /^(ver\s+)?painel$/i.test(text)) { this.navigate('painel'); }
    else if (!this.pendingImages().length && /^(minhas demandas|meus itens)$/i.test(text)) { this.showMine(); }
    else {
      this.editNew();
      const title = text.replace(/^(criar|abrir|adicionar|nova)\s+(uma\s+)?(demanda|tarefa)(\s+de)?\s*/i, '').trim() || text;
      const match = title.match(/\s+para\s+(arthur|felipe)$/i);
      const person = match ? this.people().find(item => item.login === match[1].toLowerCase()) : undefined;
      this.title = person ? title.slice(0, match!.index).trim() : title;
      this.responsible = person ? String(person.id) : '';
    }
    this.commandText = ''; this.paletteQuery = '';
  }

  onCommandEnter(event: Event) {
    if (!(event as KeyboardEvent).shiftKey) { event.preventDefault(); this.useCommand(); }
  }

  showMine() {
    this.filterPerson = String(this.me()?.id ?? '');
    this.filtersOpen.set(true); this.navigate('demandas'); void this.searchDemands();
  }

  plainText(html: string | undefined) {
    return (html ?? '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
  }
  editNew() { this.openNew(); this.editing.set(true); setTimeout(() => { if (this.editor) this.editor.nativeElement.innerHTML = ''; }); }
  async editExisting(demand: Demand) { await this.open(demand); this.editing.set(true); }
  closeEditor() { if (this.editing()) this.clearPendingImages(); this.editing.set(false); this.current.set(null); }

  pasteImages(event: ClipboardEvent) {
    const images = Array.from(event.clipboardData?.items ?? [])
      .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
      .map(item => item.getAsFile()).filter((file): file is File => !!file);
    if (!images.length) return;
    event.preventDefault();
    const text = event.clipboardData?.getData('text/plain') ?? '';
    if (text && event.target instanceof HTMLTextAreaElement) {
      const input = event.target;
      input.setRangeText(text, input.selectionStart, input.selectionEnd, 'end');
      this.commandText = input.value;
    } else if (text && event.target instanceof HTMLElement && event.target.isContentEditable) {
      const selection = window.getSelection();
      if (selection?.rangeCount) {
        const range = selection.getRangeAt(0);
        range.deleteContents();
        const node = document.createTextNode(text);
        range.insertNode(node);
        range.setStartAfter(node);
        range.collapse(true);
        selection.removeAllRanges();
        selection.addRange(range);
      }
    }
    this.addPendingImages(images);
    if (this.editing() && this.current()) void this.save();
  }

  chooseImages(event: Event) {
    const input = event.target as HTMLInputElement;
    this.addPendingImages(Array.from(input.files ?? []));
    input.value = '';
    if (this.editing() && this.current() && this.pendingImages().length) void this.save();
  }

  removePendingImage(id: number) {
    const image = this.pendingImages().find(item => item.id === id);
    if (image) URL.revokeObjectURL(image.previewUrl);
    this.pendingImages.update(items => items.filter(item => item.id !== id));
  }

  private clearPendingImages() {
    for (const image of this.pendingImages()) URL.revokeObjectURL(image.previewUrl);
    this.pendingImages.set([]);
  }

  private addPendingImages(files: File[]) {
    const accepted = files.filter(file => ['image/png', 'image/jpeg'].includes(file.type) && file.size <= 10_000_000);
    if (accepted.length !== files.length) this.error.set('Use imagens PNG ou JPEG de até 10 MB cada.');
    if (accepted.length) this.pendingImages.update(items => [...items, ...accepted.map(file => ({
      id: ++this.nextImageId, file, previewUrl: URL.createObjectURL(file)
    }))]);
  }

  async save() {
    if (this.busy()) return;
    if (!this.title.trim()) { this.error.set('Informe o título da demanda.'); return; }
    this.description = this.sanitizer.sanitize(SecurityContext.HTML, this.editor?.nativeElement.innerHTML ?? '') ?? '';
    this.busy.set(true); this.error.set('');
    const body = {titulo: this.title, descricao: this.description, status: this.status, responsavelId: this.responsible ? Number(this.responsible) : null};
    try {
      const request = this.current() ? this.http.put<Demand>(`/api/demandas/${this.current()!.id}`, body) : this.http.post<Demand>('/api/demandas', body);
      let saved = await firstValueFrom(request);
      this.current.set(saved);
      let uploaded = 0;
      let failed = 0;
      for (const image of [...this.pendingImages()]) {
        try {
          const form = new FormData(); form.append('arquivo', image.file);
          const result = await firstValueFrom(this.http.post<{id:number;url:string}>(`/api/demandas/${saved.id}/anexos`, form));
          const element = document.createElement('img'); element.src = result.url; element.alt = image.file.name;
          this.editor?.nativeElement.append(element);
          this.removePendingImage(image.id);
          uploaded++;
        } catch { failed++; }
      }
      if (uploaded) {
        this.description = this.sanitizer.sanitize(SecurityContext.HTML, this.editor?.nativeElement.innerHTML ?? '') ?? '';
        saved = await firstValueFrom(this.http.put<Demand>(`/api/demandas/${saved.id}`, {...body, descricao: this.description}));
        this.current.set(saved);
        this.attachments.set(await firstValueFrom(this.http.get<Attachment[]>(`/api/demandas/${saved.id}/anexos`)));
      }
      await this.searchDemands();
      if (failed) this.error.set(`${failed} imagem(ns) não puderam ser anexadas. Tente salvar novamente.`);
      else this.notice.set(uploaded ? 'Demanda e fotos salvas.' : 'Demanda salva.');
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { this.busy.set(false); }
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
    this.filtersOpen.set(true); this.page.set('demandas'); void this.searchDemands();
  }
  async deleteFilter(filter: SavedFilter) {
    await firstValueFrom(this.http.delete(`/api/filtros/${filter.id}`));
    this.filters.update(items => items.filter(item => item.id !== filter.id));
  }
  count(status: Status) { return this.demands().filter(item => item.status === status).length; }
  private errorText(e: unknown) { return e instanceof HttpErrorResponse && e.status === 413 ? 'A foto excede o limite de 10 MB.' : 'Não foi possível concluir a operação.'; }
}
