import { Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, Output, ViewChild, forwardRef, inject } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

export type HubOption = { value: string; label: string };

@Component({
  selector: 'hub-select',
  standalone: true,
  template: `
    <div class="select-wrap">
      <button #trigger type="button" class="select-trigger" [class.is-open]="open" [disabled]="disabled"
        [attr.aria-label]="ariaLabel" aria-haspopup="listbox" [attr.aria-expanded]="open"
        (click)="toggle()" (keydown)="onTriggerKeydown($event)">
        <span class="select-value" [class.is-placeholder]="!selectedLabel">{{ selectedLabel || placeholder }}</span>
        <svg class="select-chevron" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="m4 6 4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      @if (open) {
        <div #menu class="select-menu" role="listbox" [attr.aria-label]="ariaLabel"
          [style.top.px]="menuTop" [style.left.px]="menuLeft" [style.width.px]="menuWidth" [class.opens-up]="opensUp">
          @for (option of options; track option.value) {
            <button type="button" role="option" class="select-option" [attr.aria-selected]="option.value === currentValue"
              (click)="choose(option.value)" (keydown)="onOptionKeydown($event)">
              <span>{{ option.label }}</span>@if (option.value === currentValue) { <span class="select-check" aria-hidden="true">✓</span> }
            </button>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; position: relative; font: inherit; }
    .select-wrap { position: relative; }
    .select-trigger { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; min-height: 44px; padding: 0 12px; color: #f4f4f4; background: #191919; border: 1px solid #383838; border-radius: 8px; text-align: left; font: inherit; font-size: 12px; font-weight: 600; transition: background .2s ease, border-color .2s ease, box-shadow .2s ease; }
    .select-trigger:hover, .select-trigger.is-open { background: #222; border-color: #686868; }
    .select-trigger:focus-visible, .select-option:focus-visible { outline: 2px solid #ddd; outline-offset: 2px; }
    .select-trigger:disabled { opacity: .48; cursor: not-allowed; }
    .select-value { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .select-value.is-placeholder { color: #999; }
    .select-chevron { flex: none; color: #aaa; transition: transform .2s ease; }
    .is-open .select-chevron { transform: rotate(180deg); }
    .select-menu { position: fixed; z-index: 30; max-height: 245px; padding: 5px; overflow-y: auto; color: #f4f4f4; background: #202020; border: 1px solid #484848; border-radius: 10px; box-shadow: 0 16px 38px #0009; animation: menu-in .18s ease both; }
    .select-menu.opens-up { transform-origin: bottom; }
    .select-option { display: flex; align-items: center; justify-content: space-between; gap: 10px; width: 100%; min-height: 38px; padding: 8px 10px; color: #ddd; background: transparent; border: 0; border-radius: 6px; text-align: left; font: inherit; font-size: 12px; cursor: pointer; }
    .select-option:hover, .select-option:focus-visible { background: #333; color: white; }
    .select-option[aria-selected=true] { background: #343434; color: white; }
    .select-check { color: #d4d4d4; }
    :host(.kanban-move) .select-trigger { min-height: 34px; padding: 0 8px; font-size: 10px; }
    @keyframes menu-in { from { opacity: 0; transform: translateY(-5px) scale(.98); } to { opacity: 1; transform: translateY(0) scale(1); } }
    @media (prefers-reduced-motion: reduce) { .select-trigger, .select-chevron { transition: none; } .select-menu { animation: none; } }
  `],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => HubSelect), multi: true }]
})
export class HubSelect implements ControlValueAccessor, OnDestroy {
  @Input() options: HubOption[] = [];
  @Input() placeholder = 'Selecione';
  @Input() ariaLabel = 'Selecionar opção';
  @Input() controlled = false;
  @Input() displayValue = '';
  @Output() selectionChange = new EventEmitter<string>();
  @ViewChild('trigger') trigger?: ElementRef<HTMLButtonElement>;
  @ViewChild('menu') menu?: ElementRef<HTMLDivElement>;
  private host = inject(ElementRef<HTMLElement>);
  value = '';
  disabled = false;
  open = false;
  menuTop = 0;
  menuLeft = 0;
  menuWidth = 0;
  opensUp = false;
  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};
  private onScroll = (event: Event) => {
    if (!this.menu?.nativeElement.contains(event.target as Node)) this.close();
  };

  get currentValue() { return this.controlled ? this.displayValue : this.value; }
  get selectedLabel() { return this.options.find(option => option.value === this.currentValue)?.label ?? ''; }
  writeValue(value: string | null) { this.value = value == null ? '' : String(value); }
  registerOnChange(fn: (value: string) => void) { this.onChange = fn; }
  registerOnTouched(fn: () => void) { this.onTouched = fn; }
  setDisabledState(disabled: boolean) { this.disabled = disabled; if (disabled && this.open) this.close(); }

  toggle() { if (this.disabled) return; this.open ? this.close() : this.show(); }
  private show() {
    const rect = this.trigger?.nativeElement.getBoundingClientRect();
    if (!rect) return;
    this.opensUp = window.innerHeight - rect.bottom < 255 && rect.top > 255;
    this.menuTop = this.opensUp ? Math.max(8, rect.top - Math.min(245, this.options.length * 38 + 12) - 6) : rect.bottom + 6;
    this.menuLeft = Math.min(rect.left, Math.max(8, window.innerWidth - Math.max(rect.width, 180) - 8));
    this.menuWidth = Math.max(rect.width, 180);
    this.open = true;
    document.addEventListener('scroll', this.onScroll, true);
    setTimeout(() => {
      const options = this.optionButtons();
      (options.find(button => button.getAttribute('aria-selected') === 'true') ?? options[0])?.focus();
    });
  }
  private close(restoreFocus = false) {
    this.open = false;
    document.removeEventListener('scroll', this.onScroll, true);
    this.onTouched();
    if (restoreFocus) setTimeout(() => this.trigger?.nativeElement.focus());
  }
  choose(value: string) {
    if (this.controlled) this.selectionChange.emit(value);
    else { this.value = value; this.onChange(value); }
    this.close(true);
  }
  onTriggerKeydown(event: KeyboardEvent) {
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(event.key)) {
      event.preventDefault();
      if (!this.open) this.show();
    }
  }
  onOptionKeydown(event: KeyboardEvent) {
    const buttons = this.optionButtons();
    const current = buttons.indexOf(event.target as HTMLButtonElement);
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); this.close(true); return; }
    const next = event.key === 'ArrowDown' ? Math.min(current + 1, buttons.length - 1)
      : event.key === 'ArrowUp' ? Math.max(current - 1, 0)
      : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : -1;
    if (next >= 0) { event.preventDefault(); buttons[next]?.focus(); }
  }
  private optionButtons() { return Array.from(this.menu?.nativeElement.querySelectorAll<HTMLButtonElement>('.select-option') ?? []); }

  @HostListener('document:pointerdown', ['$event'])
  onOutsidePointer(event: PointerEvent) {
    if (this.open && !this.host.nativeElement.contains(event.target as Node)) this.close();
  }

  @HostListener('focusout', ['$event'])
  onFocusOut(event: FocusEvent) {
    if (this.open && !this.host.nativeElement.contains(event.relatedTarget as Node)) this.close();
  }

  @HostListener('window:resize')
  onViewportChange() { if (this.open) this.close(); }

  ngOnDestroy() { document.removeEventListener('scroll', this.onScroll, true); }
}
