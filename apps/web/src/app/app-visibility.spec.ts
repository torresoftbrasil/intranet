import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { App } from './app';

describe('visibilidade da execução interna', () => {
  function setup(login: 'arthur' | 'felipe') {
    TestBed.configureTestingModule({imports: [App], providers: [provideHttpClient(), provideHttpClientTesting()]});
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    app.me.set({id: login === 'arthur' ? 1 : 2, login, nome: login === 'arthur' ? 'Arthur' : 'Felipe'});
    return {fixture, app};
  }

  const demand = {
    id: 10, titulo: 'Entrega', descricao: 'Conferir a tela', status: 'EM_TESTE' as const,
    responsavelId: 2, responsavel: 'Felipe', criadoEm: '2026-10-11T10:00:00',
    atualizadoEm: '2026-10-11T10:00:00', destinadaIa: true,
    iaEstado: 'CONCLUIDA', iaResultado: 'Detalhes técnicos internos'
  };

  it('não mostra marcadores nem resultado da IA para Felipe', () => {
    const {fixture, app} = setup('felipe');
    app.page.set('demandas');
    app.listMode.set('kanban');
    app.demands.set([demand]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.kanban-ai-label')).toBeNull();
    expect(fixture.nativeElement.querySelector('.kanban-card.is-ai')).toBeNull();

    app.current.set(demand);
    app.editing.set(true);
    app.comments.set([
      {id: 1, texto: 'Detalhes de implementação', autor: 'Zyven', criadoEm: '2026-10-11T10:00:00', visibilidade: 'SOMENTE_ARTHUR', imagens: []},
      {id: 2, texto: 'Confira a entrega', autor: 'Arthur', criadoEm: '2026-10-11T10:01:00', visibilidade: 'GERAL', imagens: []}
    ]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ai-result')).toBeNull();
    expect(fixture.nativeElement.querySelector('.comment-visibility')).toBeNull();
    expect(fixture.nativeElement.querySelector('.comments-list')?.textContent).toContain('Confira a entrega');
    expect(fixture.nativeElement.querySelector('.comments-list')?.textContent).not.toContain('Detalhes de implementação');
  });

  it('mantém contexto interno e classificação de comentário para Arthur', () => {
    const {fixture, app} = setup('arthur');
    app.page.set('demandas');
    app.listMode.set('kanban');
    app.demands.set([demand]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.kanban-ai-label')?.textContent).toContain('IA');
    expect(fixture.nativeElement.querySelector('.kanban-card.is-ai')).not.toBeNull();

    app.current.set(demand);
    app.editing.set(true);
    app.comments.set([{id: 1, texto: 'Investigação', autor: 'Arthur', criadoEm: '2026-10-11T10:00:00', visibilidade: 'SOMENTE_ARTHUR', imagens: []}]);
    app.commentComposerOpen.set(true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ai-result')?.textContent).toContain('Detalhes técnicos internos');
    expect(fixture.nativeElement.querySelector('.comment-private')?.textContent).toContain('só Arthur');
    expect(fixture.nativeElement.querySelector('.comment-visibility')).not.toBeNull();
  });

  it('não altera a ordem dos cartões para Felipe por sua origem interna', () => {
    const {app} = setup('felipe');
    const regular = {...demand, id: 11, destinadaIa: false};
    app.demands.set([demand, regular]);
    expect(app.laneDemands('EM_TESTE').map(item => item.id)).toEqual([10, 11]);
    app.me.set({id: 1, login: 'arthur', nome: 'Arthur'});
    expect(app.laneDemands('EM_TESTE').map(item => item.id)).toEqual([11, 10]);
  });

  it('exige resumo humano ao enviar demanda da IA para teste pelo Kanban', async () => {
    const {fixture, app} = setup('arthur');
    app.testModal.set(demand);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.test-dialog textarea')?.placeholder).toContain('Felipe precisa testar');
    expect(fixture.nativeElement.querySelector('.test-dialog .primary')?.disabled).toBe(true);
    await app.submitTest();
    expect(app.error()).toContain('Resuma o que foi entregue');
    app.testComment = 'Confira a tela de entrega e o filtro.';
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.test-dialog .primary')?.disabled).toBe(false);
  });

  it('envia demanda IA em revisão por uma operação única, sem duplicar comentário', async () => {
    const {app} = setup('arthur');
    const http = TestBed.inject(HttpTestingController);
    app.testModal.set({...demand, iaEstado: 'AGUARDANDO_REVISAO'});
    app.testComment = 'Confira a tela de entrega e o filtro.';

    const submitted = app.submitTest();
    const handoff = http.expectOne('/api/demandas/10/enviar-teste');
    expect(handoff.request.method).toBe('POST');
    expect(handoff.request.body).toEqual({resumoTeste: 'Confira a tela de entrega e o filtro.'});
    handoff.flush({...demand, status: 'EM_TESTE', iaEstado: 'CONCLUIDA'});
    await Promise.resolve();

    http.expectOne(request => request.method === 'GET' && request.url === '/api/demandas').flush([]);
    http.expectOne('/api/demandas/recentes').flush([]);
    await submitted;
    http.expectNone('/api/demandas/10/comentarios');
    expect(app.testModal()).toBeNull();
    expect(app.notice()).toBe('Demanda enviada para teste.');
  });
});
