import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Component, ElementRef, HostListener, ViewChild, computed, inject, signal, WritableSignal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { DomSanitizer } from '@angular/platform-browser';
import { SecurityContext } from '@angular/core';
import { HubOption, HubSelect } from './hub-select';

type Status = 'AGUARDANDO_DESENVOLVIMENTO' | 'EM_DESENVOLVIMENTO' | 'DESENVOLVIMENTO_EM_PROGRESSO' | 'EM_TESTE' | 'REABERTA' | 'ENCERRADA';
type Person = { id: number; login: string; nome: string };
type Demand = { id: number; titulo: string; descricao?: string; status: Status; responsavelId: number | ''; responsavel: string; criadoEm: string; atualizadoEm: string };
type RecentDemand = Pick<Demand, 'id' | 'titulo' | 'responsavel' | 'criadoEm'>;
type Attachment = { id: number; nome: string; tipo: string; tamanho: number };
type PendingImage = { id: number; file: File; previewUrl: string };
type SavedFilter = { id: number; nome: string; texto: string | null; status: Status | null; responsavelId: number | null };
type Comment = { id: number; texto: string; autor: string; criadoEm: string; imagens: {id: number; nome: string; url: string}[] };
const openStatuses: {value: Status; label: string}[] = [
  {value: 'AGUARDANDO_DESENVOLVIMENTO', label: 'Aguardando desenvolvimento'},
  {value: 'EM_DESENVOLVIMENTO', label: 'Em desenvolvimento'},
  {value: 'DESENVOLVIMENTO_EM_PROGRESSO', label: 'Desenvolvimento em progresso'},
  {value: 'EM_TESTE', label: 'Em teste'},
  {value: 'REABERTA', label: 'Reaberta'}
];
const statuses: {value: Status; label: string}[] = [...openStatuses, {value: 'ENCERRADA', label: 'Encerrada'}];

@Component({selector: 'app-root', standalone: true, imports: [CommonModule, FormsModule, HubSelect], templateUrl: './app.html', styleUrl: './app.scss'})
export class App {
  private http = inject(HttpClient);
  private sanitizer = inject(DomSanitizer);
  @ViewChild('editor') editor?: ElementRef<HTMLDivElement>;
  @ViewChild('paletteInput') paletteInput?: ElementRef<HTMLInputElement>;
  @ViewChild('promptInput') promptInput?: ElementRef<HTMLTextAreaElement>;
  statuses = statuses;
  openStatuses = openStatuses;
  statusOptions: HubOption[] = statuses;
  quickStatusOptions: HubOption[] = openStatuses;
  filterStatusOptions: HubOption[] = [{value: '', label: 'Todos os status'}, ...statuses];
  kanbanStatusOptions: HubOption[] = [{value: '', label: 'Todos os status'}, ...openStatuses];
  bulkStatusOptions: HubOption[] = [{value: '', label: 'Manter status'}, ...statuses];
  me = signal<Person | null>(null);
  page = signal<'inicio' | 'demandas' | 'painel'>('inicio');
  demands = signal<Demand[]>([]);
  recentDemands = signal<RecentDemand[]>([]);
  people = signal<Person[]>([]);
  personOptions = computed<HubOption[]>(() => [{value: '', label: 'Sem responsável'}, ...this.people().map(person => ({value: String(person.id), label: person.nome}))]);
  filterPersonOptions = computed<HubOption[]>(() => [{value: '', label: 'Todos os responsáveis'}, ...this.people().map(person => ({value: String(person.id), label: person.nome}))]);
  filters = signal<SavedFilter[]>([]);
  attachments = signal<Attachment[]>([]);
  pendingImages = signal<PendingImage[]>([]);
  comments = signal<Comment[]>([]);
  commentImages = signal<PendingImage[]>([]);
  testImages = signal<PendingImage[]>([]);
  commentComposerOpen = signal(false);
  testModal = signal<Demand | null>(null);
  listMode = signal<'cards' | 'kanban'>('cards');
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
  commentDraft = '';
  testComment = '';
  testResponsible = '';
  private testMoved = false;

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
    this.clearImageList(this.commentImages); this.clearImageList(this.testImages);
    this.quickTitle = ''; this.quickTitleReady = false; this.commandText = ''; this.quickResponsible = '';
    this.quickStatus = 'AGUARDANDO_DESENVOLVIMENTO';
    this.quickSavedId = null; this.quickImageHtml = '';
    await firstValueFrom(this.http.post('/api/logout', {}));
    this.me.set(null); this.current.set(null); this.demands.set([]); this.recentDemands.set([]);
    await firstValueFrom(this.http.get('/api/csrf'));
  }
  async refresh() {
    const [people, filters] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>('/api/usuarios')),
      firstValueFrom(this.http.get<SavedFilter[]>('/api/filtros'))
    ]);
    this.people.set(people); this.filters.set(filters);
    if (!this.quickResponsible) this.quickResponsible = String(this.me()?.id ?? '');
    await Promise.all([this.searchDemands(), this.loadRecentDemands()]);
  }
  async loadRecentDemands() {
    try { this.recentDemands.set(await firstValueFrom(this.http.get<RecentDemand[]>('/api/demandas/recentes'))); }
    catch { this.error.set('Não foi possível carregar as demandas recentes.'); }
  }
  async searchDemands() {
    this.selected.set([]);
    let params = new HttpParams();
    if (this.search.trim()) params = params.set('texto', this.search.trim());
    if (this.filterStatus) params = params.set('status', this.filterStatus);
    if (this.filterPerson) params = params.set('responsavelId', this.filterPerson);
    if (this.listMode() === 'kanban') params = params.set('abertas', 'true');
    try { this.demands.set(await firstValueFrom(this.http.get<Demand[]>('/api/demandas', {params}))); }
    catch { this.error.set('Não foi possível carregar as demandas.'); }
  }
  clearFilters() { this.search = ''; this.filterStatus = ''; this.filterPerson = ''; void this.searchDemands(); }
  statusLabel(value: string) { return statuses.find(item => item.value === value)?.label ?? value; }
  async open(demand: Pick<Demand, 'id'>) {
    this.error.set('');
    try {
      this.clearPendingImages();
      const full = await firstValueFrom(this.http.get<Demand>(`/api/demandas/${demand.id}`));
      this.current.set(full); this.title = full.titulo; this.description = full.descricao ?? '';
      this.status = full.status; this.responsible = full.responsavelId ? String(full.responsavelId) : '';
      const [attachments, comments] = await Promise.all([
        firstValueFrom(this.http.get<Attachment[]>(`/api/demandas/${demand.id}/anexos`)),
        firstValueFrom(this.http.get<Comment[]>(`/api/demandas/${demand.id}/comentarios`))
      ]);
      this.attachments.set(attachments); this.comments.set(comments);
      setTimeout(() => { if (this.editor) this.editor.nativeElement.innerHTML = this.sanitizer.sanitize(SecurityContext.HTML, this.description) ?? ''; });
    } catch { this.error.set('Não foi possível abrir a demanda.'); }
  }
  editing = signal(false);
  menuOpen = signal(false);
  paletteOpen = signal(false);
  filtersOpen = signal(false);
  commandText = '';
  quickTitle = '';
  quickTitleReady = false;
  quickStatus: Status = 'AGUARDANDO_DESENVOLVIMENTO';
  quickResponsible = '';
  private quickSavedId: number | null = null;
  private quickImageHtml = '';
  paletteQuery = '';

  @HostListener('document:keydown', ['$event'])
  handleShortcut(event: KeyboardEvent) {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault(); this.menuOpen.set(false); this.paletteOpen.update(open => !open);
      if (this.paletteOpen()) setTimeout(() => this.paletteInput?.nativeElement.focus());
    } else if (event.key === 'Escape') {
      this.paletteOpen.set(false); this.menuOpen.set(false);
      if (this.testModal() && !this.busy()) this.closeTestModal();
    }
  }

  navigate(page: 'inicio' | 'demandas' | 'painel') {
    if (page === 'painel' && this.listMode() === 'kanban') {
      this.listMode.set('cards'); this.search = ''; this.filterStatus = ''; this.filterPerson = '';
      void this.searchDemands();
    }
    this.page.set(page); this.closeEditor(); this.menuOpen.set(false); this.paletteOpen.set(false);
  }

  useCommand(value = this.paletteQuery) {
    const text = value.trim(); if (!text) return;
    this.paletteOpen.set(false);
    if (/^(buscar|pesquisar)\s+/i.test(text)) {
      this.listMode.set('cards');
      this.search = text.replace(/^(buscar|pesquisar)\s+/i, '');
      this.filtersOpen.set(true); this.navigate('demandas'); void this.searchDemands();
    } else if (/^(ver\s+)?painel$/i.test(text)) { this.navigate('painel'); }
    else if (/^(kanban|pendências|minhas demandas|meus itens)$/i.test(text)) { this.showKanban(); }
    else {
      this.editNew();
      const title = text.replace(/^(criar|abrir|adicionar|nova)\s+(uma\s+)?(demanda|tarefa)(\s+de)?\s*/i, '').trim() || text;
      const match = title.match(/\s+para\s+(arthur|felipe)$/i);
      const person = match ? this.people().find(item => item.login === match[1].toLowerCase()) : undefined;
      this.quickTitle = (person ? title.slice(0, match!.index).trim() : title).slice(0, 180);
      this.quickTitleReady = true;
      this.quickResponsible = person ? String(person.id) : String(this.me()?.id ?? '');
      setTimeout(() => this.promptInput?.nativeElement.focus());
    }
    this.paletteQuery = '';
  }

  onPromptEnter(event: Event) {
    if ((event as KeyboardEvent).shiftKey) return;
    event.preventDefault();
    this.advancePrompt();
  }

  focusPrompt(event: Event) {
    event.preventDefault();
    this.promptInput?.nativeElement.focus();
  }

  advancePrompt() {
    if (this.quickTitleReady) { void this.saveFromPrompt(); return; }
    const [firstLine, ...rest] = this.commandText.split('\n');
    const title = firstLine.trim();
    if (!title) { this.error.set('Escreva um título para a demanda.'); return; }
    if (title.length > 180) { this.error.set('O título deve ter até 180 caracteres.'); return; }
    this.quickTitle = title;
    this.quickTitleReady = true;
    this.commandText = rest.join('\n').trimStart();
    this.error.set('');
    setTimeout(() => this.promptInput?.nativeElement.focus());
  }

  private promptDescription() {
    const container = document.createElement('div');
    container.textContent = this.commandText.trim();
    return container.innerHTML.replace(/\n/g, '<br>') + this.quickImageHtml;
  }

  async saveFromPrompt() {
    if (this.busy()) return;
    if (!this.quickTitle.trim()) { this.error.set('Escreva um título para a demanda.'); return; }
    this.busy.set(true); this.error.set('');
    const body = {titulo: this.quickTitle.trim(), descricao: this.promptDescription(),
      status: this.quickStatus,
      responsavelId: this.quickResponsible ? Number(this.quickResponsible) : null};
    try {
      const request = this.quickSavedId
        ? this.http.put<Demand>(`/api/demandas/${this.quickSavedId}`, body)
        : this.http.post<Demand>('/api/demandas', body);
      const saved = await firstValueFrom(request);
      this.quickSavedId = saved.id;
      const uploads = await this.uploadPendingImages(saved.id);
      if (uploads.uploaded) {
        this.quickImageHtml += uploads.html;
        await firstValueFrom(this.http.put<Demand>(`/api/demandas/${saved.id}`, {...body, descricao: this.promptDescription()}));
      }
      await Promise.all([this.searchDemands(), this.loadRecentDemands()]);
      if (uploads.failed) {
        this.error.set(`${uploads.failed} imagem(ns) não puderam ser anexadas. Tente salvar novamente.`);
      } else {
        this.notice.set('Demanda salva.');
        this.quickTitle = ''; this.quickTitleReady = false; this.commandText = '';
        this.quickSavedId = null; this.quickImageHtml = '';
        this.quickStatus = 'AGUARDANDO_DESENVOLVIMENTO';
        this.quickResponsible = String(this.me()?.id ?? '');
        this.clearPendingImages();
      }
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { this.busy.set(false); }
  }

  showKanban() {
    this.search = ''; this.filterStatus = '';
    this.filterPerson = '';
    this.listMode.set('kanban');
    this.filtersOpen.set(false); this.navigate('demandas'); void this.searchDemands();
  }

  showAll() {
    this.search = ''; this.filterStatus = ''; this.filterPerson = '';
    this.listMode.set('cards');
    this.filtersOpen.set(false); this.navigate('demandas'); void this.searchDemands();
  }

  plainText(html: string | undefined) {
    const spaced = (html ?? '').replace(/<(?:br|\/p|\/div|\/li)\b[^>]*>/gi, ' ');
    return (new DOMParser().parseFromString(spaced, 'text/html').body.textContent ?? '')
      .replace(/\s+/g, ' ').trim();
  }
  openedAt(value: string) {
    const date = new Date(value);
    const today = new Date();
    if (date.toDateString() === today.toDateString())
      return `Hoje, ${new Intl.DateTimeFormat('pt-BR', {hour: '2-digit', minute: '2-digit'}).format(date)}`;
    return new Intl.DateTimeFormat('pt-BR', {day: '2-digit', month: '2-digit', year: 'numeric'}).format(date);
  }
  commentDate(value: string) {
    return new Intl.DateTimeFormat('pt-BR', {day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'}).format(new Date(value));
  }
  editNew() { this.navigate('inicio'); setTimeout(() => this.promptInput?.nativeElement.focus()); }
  async editExisting(demand: Pick<Demand, 'id'>) { await this.open(demand); this.editing.set(true); }
  closeEditor() {
    if (this.editing()) this.clearPendingImages();
    this.clearImageList(this.commentImages); this.commentDraft = ''; this.commentComposerOpen.set(false);
    this.editing.set(false); this.current.set(null); this.comments.set([]);
  }

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

  private addImageList(files: File[], target: WritableSignal<PendingImage[]>) {
    const accepted = files.filter(file => ['image/png', 'image/jpeg'].includes(file.type) && file.size <= 10_000_000);
    if (accepted.length !== files.length) this.error.set('Use imagens PNG ou JPEG de até 10 MB cada.');
    if (target().length + accepted.length > 5) { this.error.set('Use até 5 imagens por comentário.'); return; }
    target.update(items => [...items, ...accepted.map(file => ({id: ++this.nextImageId, file, previewUrl: URL.createObjectURL(file)}))]);
  }

  removeCommentImage(id: number, target: WritableSignal<PendingImage[]>) {
    const image = target().find(item => item.id === id);
    if (image) URL.revokeObjectURL(image.previewUrl);
    target.update(items => items.filter(item => item.id !== id));
  }

  private clearImageList(target: WritableSignal<PendingImage[]>) {
    for (const image of target()) URL.revokeObjectURL(image.previewUrl);
    target.set([]);
  }

  pasteCommentImage(event: ClipboardEvent, target: 'comment' | 'test') {
    const files = Array.from(event.clipboardData?.items ?? [])
      .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
      .map(item => item.getAsFile()).filter((file): file is File => !!file);
    if (!files.length) return;
    event.preventDefault();
    const text = event.clipboardData?.getData('text/plain');
    if (text && event.target instanceof HTMLTextAreaElement) {
      const input = event.target;
      input.setRangeText(text, input.selectionStart, input.selectionEnd, 'end');
      if (target === 'comment') this.commentDraft = input.value;
      else this.testComment = input.value;
    }
    this.addImageList(files, target === 'comment' ? this.commentImages : this.testImages);
  }

  chooseCommentImages(event: Event, target: 'comment' | 'test') {
    const input = event.target as HTMLInputElement;
    this.addImageList(Array.from(input.files ?? []), target === 'comment' ? this.commentImages : this.testImages);
    input.value = '';
  }

  private async postComment(demandId: number, message: string, images: PendingImage[]) {
    const form = new FormData();
    form.append('texto', message.trim());
    for (const image of images) form.append('imagem', image.file, image.file.name);
    await firstValueFrom(this.http.post<Comment>(`/api/demandas/${demandId}/comentarios`, form));
  }

  async loadComments(demandId: number) {
    this.comments.set(await firstValueFrom(this.http.get<Comment[]>(`/api/demandas/${demandId}/comentarios`)));
  }

  async submitComment() {
    const current = this.current();
    if (!current || this.busy()) return;
    if (!this.commentDraft.trim() && !this.commentImages().length) { this.error.set('Escreva um comentário ou cole uma imagem.'); return; }
    this.busy.set(true); this.error.set('');
    try {
      await this.postComment(current.id, this.commentDraft, this.commentImages());
      this.clearImageList(this.commentImages); this.commentDraft = ''; this.commentComposerOpen.set(false);
      await this.loadComments(current.id);
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { this.busy.set(false); }
  }

  laneDemands(status: Status) { return this.demands().filter(item => item.status === status); }

  startDrag(event: DragEvent, demand: Demand) {
    event.dataTransfer?.setData('text/plain', String(demand.id));
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move';
  }

  allowDrop(event: DragEvent) { event.preventDefault(); }

  dropDemand(event: DragEvent, status: Status) {
    event.preventDefault();
    const id = Number(event.dataTransfer?.getData('text/plain'));
    const demand = this.demands().find(item => item.id === id);
    if (demand) this.requestStatusChange(demand, status);
  }

  requestStatusChange(demand: Demand, status: Status) {
    if (status === demand.status || this.busy()) return;
    if (status === 'EM_TESTE') {
      this.testResponsible = String(this.people().find(person => person.login === 'felipe')?.id ?? demand.responsavelId ?? '');
      this.testComment = ''; this.testMoved = false; this.testModal.set(demand);
      return;
    }
    void this.moveDemand(demand, status, demand.responsavelId || null);
  }

  private async moveDemand(demand: Demand, status: Status, responsibleId: number | null) {
    this.busy.set(true); this.error.set('');
    try {
      await firstValueFrom(this.http.put<Demand>(`/api/demandas/${demand.id}`, {
        titulo: demand.titulo, descricao: demand.descricao ?? '', status, responsavelId: responsibleId
      }));
      await Promise.all([this.searchDemands(), this.loadRecentDemands()]);
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { this.busy.set(false); }
  }

  closeTestModal() {
    this.testModal.set(null); this.testComment = ''; this.testResponsible = '';
    this.testMoved = false; this.clearImageList(this.testImages);
  }

  async submitTest() {
    const demand = this.testModal();
    if (!demand || this.busy()) return;
    this.busy.set(true); this.error.set('');
    try {
      if (!this.testMoved) {
        await firstValueFrom(this.http.put<Demand>(`/api/demandas/${demand.id}`, {
          titulo: demand.titulo, descricao: demand.descricao ?? '', status: 'EM_TESTE',
          responsavelId: this.testResponsible ? Number(this.testResponsible) : null
        }));
        this.testMoved = true;
        await Promise.all([this.searchDemands(), this.loadRecentDemands()]);
      }
      if (this.testComment.trim() || this.testImages().length)
        await this.postComment(demand.id, this.testComment, this.testImages());
      this.closeTestModal();
      this.notice.set('Demanda enviada para teste.');
    } catch (e) { this.error.set(this.errorText(e)); }
    finally { this.busy.set(false); }
  }

  private async uploadPendingImages(demandId: number) {
    let html = '';
    let uploaded = 0;
    let failed = 0;
    for (const image of [...this.pendingImages()]) {
      try {
        const form = new FormData(); form.append('arquivo', image.file);
        const result = await firstValueFrom(this.http.post<{id:number;url:string}>(`/api/demandas/${demandId}/anexos`, form));
        const element = document.createElement('img'); element.src = result.url; element.alt = image.file.name;
        html += element.outerHTML;
        this.removePendingImage(image.id);
        uploaded++;
      } catch { failed++; }
    }
    return {html, uploaded, failed};
  }

  async save(): Promise<boolean> {
    if (this.busy()) return false;
    if (!this.title.trim()) { this.error.set('Informe o título da demanda.'); return false; }
    this.description = this.sanitizer.sanitize(SecurityContext.HTML, this.editor?.nativeElement.innerHTML ?? '') ?? '';
    this.busy.set(true); this.error.set('');
    const body = {titulo: this.title, descricao: this.description, status: this.status, responsavelId: this.responsible ? Number(this.responsible) : null};
    try {
      const request = this.current() ? this.http.put<Demand>(`/api/demandas/${this.current()!.id}`, body) : this.http.post<Demand>('/api/demandas', body);
      let saved = await firstValueFrom(request);
      this.current.set(saved);
      const uploads = await this.uploadPendingImages(saved.id);
      if (uploads.uploaded) {
        this.editor?.nativeElement.insertAdjacentHTML('beforeend', uploads.html);
        this.description = this.sanitizer.sanitize(SecurityContext.HTML, this.editor?.nativeElement.innerHTML ?? '') ?? '';
        saved = await firstValueFrom(this.http.put<Demand>(`/api/demandas/${saved.id}`, {...body, descricao: this.description}));
        this.current.set(saved);
        this.attachments.set(await firstValueFrom(this.http.get<Attachment[]>(`/api/demandas/${saved.id}/anexos`)));
      }
      await Promise.all([this.searchDemands(), this.loadRecentDemands()]);
      if (uploads.failed) {
        this.error.set(`${uploads.failed} imagem(ns) não puderam ser anexadas. Tente salvar novamente.`);
        return false;
      }
      this.notice.set(uploads.uploaded ? 'Demanda e fotos salvas.' : 'Demanda salva.');
      return true;
    } catch (e) { this.error.set(this.errorText(e)); return false; }
    finally { this.busy.set(false); }
  }
  async closeDemand() {
    if (!this.current() || this.busy()) return;
    if (this.pendingImages().length && !await this.save()) return;
    this.status = 'ENCERRADA';
    if (await this.save()) {
      this.notice.set('Demanda encerrada.');
      this.closeEditor();
    }
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
      this.notice.set(`${this.selected().length} demanda(s) atualizada(s).`); this.selected.set([]);
      await Promise.all([this.searchDemands(), this.loadRecentDemands()]);
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
    this.listMode.set('cards');
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
