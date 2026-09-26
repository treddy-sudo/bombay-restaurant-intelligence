import { FormEvent, useState } from 'react';
import './SourceMappingEditor.css';

type Mapping={id:string;sourceKey:string;sourceColumn:string;canonicalField:string};
type ApiFn=<T>(path:string,options?:RequestInit)=>Promise<T>;

const canonicalFields=[
  ['vendor','Vendor'],
  ['employee','Employee'],
  ['amount','Amount'],
  ['category','Category / item description'],
  ['description','Description'],
  ['businessDate','Business date'],
  ['transactionType','Transaction type']
] as const;

export function SourceMappingEditor({api,onNotice}:{api:ApiFn;onNotice:(message:string)=>void}){
  const [sourceKey,setSourceKey]=useState('');
  const [sourceColumn,setSourceColumn]=useState('');
  const [canonicalField,setCanonicalField]=useState('vendor');
  const [items,setItems]=useState<Mapping[]>([]);
  const [busy,setBusy]=useState(false);

  const load=async()=>{
    const key=sourceKey.trim();
    if(!key)return;
    setBusy(true);
    try{
      const result=await api<Mapping[]>(`/api/source-column-mappings?sourceKey=${encodeURIComponent(key)}`);
      setItems(result);
      if(!result.length)onNotice(`No saved mappings yet for ${key}`);
    }catch(error){onNotice((error as Error).message)}finally{setBusy(false)}
  };

  const save=async(event:FormEvent<HTMLFormElement>)=>{
    event.preventDefault();
    const key=sourceKey.trim();
    const column=sourceColumn.trim();
    if(!key||!column)return;
    setBusy(true);
    try{
      await api<Mapping>('/api/source-column-mappings',{method:'POST',body:JSON.stringify({sourceKey:key,sourceColumn:column,canonicalField})});
      const result=await api<Mapping[]>(`/api/source-column-mappings?sourceKey=${encodeURIComponent(key)}`);
      setItems(result);
      setSourceColumn('');
      onNotice(`Saved mapping for ${column} → ${canonicalFields.find(([value])=>value===canonicalField)?.[1]||canonicalField}`);
    }catch(error){onNotice((error as Error).message)}finally{setBusy(false)}
  };

  return <div className="mapping-editor">
    <div className="mapping-intro">
      <p className="subtle">Use the filename without its extension as the source key. Example: <b>purchase-report.xlsx</b> → <b>purchase-report</b>.</p>
      <div className="mapping-source">
        <input value={sourceKey} onChange={event=>setSourceKey(event.target.value)} placeholder="Source key, e.g. purchase-report"/>
        <button type="button" className="ghost" disabled={busy||!sourceKey.trim()} onClick={load}>{busy?'Loading…':'Load mappings'}</button>
      </div>
    </div>
    <form className="mapping-form" onSubmit={save}>
      <input value={sourceColumn} onChange={event=>setSourceColumn(event.target.value)} placeholder="Workbook column, e.g. Supplier Name" required/>
      <span className="mapping-arrow">→</span>
      <select value={canonicalField} onChange={event=>setCanonicalField(event.target.value)}>{canonicalFields.map(([value,label])=><option key={value} value={value}>{label}</option>)}</select>
      <button className="primary" disabled={busy||!sourceKey.trim()}>{busy?'Saving…':'Save mapping'}</button>
    </form>
    <div className="mapping-list">
      {items.map(item=><button type="button" className="mapping-row" key={item.id} onClick={()=>{setSourceColumn(item.sourceColumn);setCanonicalField(item.canonicalField)}}>
        <span>{item.sourceColumn}</span><b>→</b><strong>{canonicalFields.find(([value])=>value===item.canonicalField)?.[1]||item.canonicalField}</strong><small>Edit</small>
      </button>)}
      {!items.length&&<div className="empty compact">Load a source key to view or edit its saved mappings.</div>}
    </div>
  </div>;
}
