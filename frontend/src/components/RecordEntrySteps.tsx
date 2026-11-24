interface Props { step: 1 | 2 | 3 }

export function RecordEntrySteps({ step }: Props) {
  return <ol className="record-entry-steps" aria-label="Entry progress">
    {['Record details', 'Record breakdown', 'Review'].map((label, index) => <li key={label}
      className={'step-label'+(index+1 === step ? ' current' : '')} aria-current={index+1 === step ? 'step' : undefined}>
      {index+1} - {label}
    </li>)}
  </ol>
}
