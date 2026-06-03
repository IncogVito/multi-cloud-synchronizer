import { Component, computed, input, output } from '@angular/core';
import { MonthSummaryResponse } from '../../../core/api/generated/model/monthSummaryResponse';
import { SourceFilter } from '../photos-toolbar/photos-toolbar.component';

export interface YearGroup {
  year: number;
  months: MonthSummaryResponse[];
  total: number;
}

@Component({
  selector: 'app-months-nav',
  standalone: true,
  imports: [],
  templateUrl: './months-nav.component.html',
  styleUrl: './months-nav.component.scss'
})
export class MonthsNavComponent {
  monthsSummary = input<MonthSummaryResponse[]>([]);
  activeMonth = input<string | null>(null);
  sourceFilter = input<SourceFilter>('all');
  collapsed = input(false);

  monthSelected = output<string | null>();

  monthNumber(yearMonth: string): number {
    return parseInt(yearMonth.slice(5, 7), 10);
  }

  shortYear(year: number): string {
    return String(year).slice(2);
  }

  countForMonth(month: MonthSummaryResponse): number {
    const f = this.sourceFilter();
    if (f === 'icloud') return month.icloudOnlyCount;
    if (f === 'iphone') return month.iphoneOnlyCount;
    return month.photoCount;
  }

  yearGroups = computed<YearGroup[]>(() => {
    const groups = new Map<number, MonthSummaryResponse[]>();
    for (const month of this.monthsSummary()) {
      const year = parseInt(month.yearMonth.slice(0, 4), 10);
      if (!groups.has(year)) groups.set(year, []);
      groups.get(year)!.push(month);
    }
    return Array.from(groups.entries())
      .sort((a, b) => b[0] - a[0])
      .map(([year, months]) => ({ year, months, total: months.reduce((s, m) => s + this.countForMonth(m), 0) }));
  });

  allPhotosCount = computed(() =>
    this.monthsSummary().reduce((s, m) => s + this.countForMonth(m), 0)
  );
}
