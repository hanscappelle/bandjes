'use strict';

document.addEventListener('DOMContentLoaded', function () {
    var filter = document.getElementById('latest-results-filter');
    var favoritesOnly = document.getElementById('latest-results-favorites-only');
    var filterQuery = document.getElementById('results-filter-query');
    var filterFavoritesOnly = document.getElementById('results-filter-favorites-only');
    var filtersForm = document.querySelector('.filters');
    if (filtersForm) {
        filtersForm.querySelectorAll('select').forEach(function (select) {
            select.addEventListener('change', function () {
                if (filterQuery && filter) {
                    filterQuery.value = filter.value;
                }
                if (filterFavoritesOnly && favoritesOnly) {
                    filterFavoritesOnly.value = favoritesOnly.checked ? '1' : '0';
                }
                filtersForm.submit();
            });
        });
    }

    var table = document.getElementById('latest-results-table');
    var count = document.getElementById('latest-results-count');
    var emptyRow = document.getElementById('latest-results-empty');

    if (!filter || !table || !count || !emptyRow) {
        return;
    }

    var rows = Array.prototype.filter.call(
        table.querySelectorAll('tbody tr'),
        function (row) {
            return row !== emptyRow;
        }
    );
    var total = rows.length;
    rows.forEach(function (row, index) {
        row.dataset.originalOrder = String(index);
    });
    var sortColumn = null;
    var sortDirection = 1;
    var collator = new Intl.Collator(undefined, {
        numeric: true,
        sensitivity: 'base'
    });

    function sortValue(row, column) {
        var cell = row.cells[column];
        var explicitValue = cell.getAttribute('data-sort-value');
        if (explicitValue !== null) {
            return explicitValue;
        }

        var link = cell.querySelector('a[data-sort-value]');
        return link ? link.getAttribute('data-sort-value') : cell.textContent.trim();
    }

    function applyFilterAndSort() {
        var term = filter.value.trim().toLocaleLowerCase();
        var visible = 0;

        if (sortColumn !== null) {
            rows.sort(function (left, right) {
                var leftValue = sortValue(left, sortColumn);
                var rightValue = sortValue(right, sortColumn);
                var comparison;

                if (sortColumn === 0 || sortColumn === 2) {
                    comparison = Number(leftValue) - Number(rightValue);
                } else {
                    comparison = collator.compare(leftValue, rightValue);
                }

                return comparison === 0
                    ? Number(left.dataset.originalOrder) - Number(right.dataset.originalOrder)
                    : comparison * sortDirection;
            });
        }

        rows.forEach(function (row) {
            var matchesText = row.textContent.toLocaleLowerCase().indexOf(term) !== -1;
            var matchesFavorite = !favoritesOnly
                || !favoritesOnly.checked
                || row.dataset.favorite === '1';
            row.hidden = !matchesText || !matchesFavorite;
            if (!row.hidden) {
                visible += 1;
            }
            table.tBodies[0].appendChild(row);
        });

        emptyRow.hidden = visible !== 0;
        emptyRow.cells[0].textContent = favoritesOnly && favoritesOnly.checked
            ? 'No favorite tyres match these filters.'
            : 'No tyres match these filters.';
        table.tBodies[0].appendChild(emptyRow);
        count.textContent = term || (favoritesOnly && favoritesOnly.checked)
            ? visible + ' of ' + total + ' tyres'
            : total + ' tyres';
    }

    filter.addEventListener('input', function () {
        if (filterQuery) {
            filterQuery.value = filter.value;
        }
        table.querySelectorAll('.favorite-form input[name="filter"]').forEach(function (input) {
            input.value = filter.value;
        });
        applyFilterAndSort();
    });

    if (favoritesOnly) {
        favoritesOnly.addEventListener('change', function () {
            var value = favoritesOnly.checked ? '1' : '0';
            if (filterFavoritesOnly) {
                filterFavoritesOnly.value = value;
            }
            table.querySelectorAll('.favorite-form input[name="favorites_only"]').forEach(function (input) {
                input.value = value;
            });
            applyFilterAndSort();
        });
    }

    table.querySelectorAll('thead [data-sort-column]').forEach(function (button) {
        var column = Number(button.dataset.sortColumn);
        var header = button.closest('th');

        button.addEventListener('click', function () {
            if (sortColumn === column) {
                sortDirection *= -1;
            } else {
                sortColumn = column;
                sortDirection = 1;
            }

            table.querySelectorAll('thead th[aria-sort]').forEach(function (cell) {
                cell.setAttribute('aria-sort', 'none');
            });
            header.setAttribute(
                'aria-sort',
                sortDirection === 1 ? 'ascending' : 'descending'
            );
            applyFilterAndSort();
        });
    });

    applyFilterAndSort();
});
