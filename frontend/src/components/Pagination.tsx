interface Props {page: number; size: number; totalItems: number; busy?: boolean; onPage:(page:number)=>void; onSize:(size:number)=>void; label?: string}
export function Pagination({page,size,totalItems,busy=false,onPage,onSize,label='Records'}:Props) {
  const totalPages=Math.ceil(totalItems/size)
  const pages=[...new Set([0,page-1,page,page+1,totalPages-1].filter(p=>p>=0&&p<totalPages))].sort((a,b)=>a-b)
  return <nav className="pagination" aria-label={`${label} pagination`}>
    <span role="status">Showing {totalItems? page*size+1:0}-{Math.min((page+1)*size,totalItems)} of {totalItems}</span>
    <div className="pagination-pages"><button className="secondary" disabled={busy||page===0} onClick={()=>onPage(page-1)}>Previous</button>
      {pages.map((p,i)=><span key={p}>{i>0&&p-pages[i-1]>1&&<span className="pagination-ellipsis" aria-hidden="true">...</span>}<button className={p===page?'':'secondary'} aria-label={`Page ${p+1}`} aria-current={p===page?'page':undefined} disabled={busy||p===page} onClick={()=>onPage(p)}>{p+1}</button></span>)}
      <button className="secondary" disabled={busy||page+1>=totalPages} onClick={()=>onPage(page+1)}>Next</button>
    </div><label>Rows per page<select value={size} disabled={busy} onChange={e=>onSize(Number(e.target.value))}>{[10,25,50].map(n=><option key={n} value={n}>{n}</option>)}</select></label>
  </nav>
}
