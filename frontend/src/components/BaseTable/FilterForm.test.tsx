import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

import FilterForm from './FilterForm'

it('normalizes custom filter input before publishing it and tolerates an absent optional renderer', () => {
  const change = vi.fn()
  render(
    <FilterForm
      queryKey={['filters']}
      searchResetVersion={0}
      values={{ keyword: 'old', optional: '' }}
      filters={{
        fields: [
          {
            name: 'keyword',
            type: 'custom',
            normalize: (value) => String(value).trim().toLowerCase(),
            render: ({ value, filters, onChange }) => (
              <button onClick={() => onChange('  NEXT  ')}>
                {String(value)}:{filters.keyword}
              </button>
            )
          },
          { name: 'optional', type: 'custom' }
        ]
      }}
      onFieldChange={change}
    />
  )
  fireEvent.click(screen.getByRole('button', { name: 'old:old' }))
  expect(change).toHaveBeenCalledExactlyOnceWith('keyword', 'next')
})
